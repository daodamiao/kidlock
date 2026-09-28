package com.kidlock.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter 主控台 + 原生能力桥接。
 * Dart 侧通过 MethodChannel("com.kidlock.app/bridge") 读写配置、查状态、控制服务。
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.kidlock.app/bridge"
        private const val TAG = "KidLock.Main"
        private const val REQ_STORAGE = 1001
        private const val REQ_GATE = 1002

        /** 离开 App 超过该时长后回来需重新做家长验证 */
        private const val REGATE_AFTER_MS = 3 * 60 * 1000L
    }

    /** 是否已弹出家长验证页，避免重复启动 */
    private var gateInProgress = false

    /** 本次进入是否已通过家长验证（进程内首次进入必须验证） */
    private var gatePassed = false

    /** 最近一次进入后台的时间戳，用于判断“离开多久后需要重新验证” */
    private var lastPauseAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissionIfNeeded()
        // 家长验证统一放在 onResume 里做（此时 Activity 已 RESUMED，startActivityForResult 最可靠）
    }

    /**
     * 打开 App 前的家长验证：未放行则弹出门禁页。
     * 规则：
     *  · 每次冷启动（进程内首次进入）都必须验证序列 —— 防止孩子自己打开 App。
     *  · 短暂切到后台再回来（如去系统设置授权）不重复要求，避免家长反复输入。
     *  · 离开 App 超过 REGATE_AFTER_MS 再回来时重新验证。
     */
    private fun ensureGate() {
        try {
            if (gatePassed) return
            if (gateInProgress) return
            gateInProgress = true
            startActivityForResult(Intent(this, GateActivity::class.java), REQ_GATE)
        } catch (t: Throwable) {
            Log.w(TAG, "ensureGate failed", t)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_GATE) {
            gateInProgress = false
            if (resultCode == RESULT_OK) {
                gatePassed = true
            } else {
                // 未通过验证：不展示 App 界面，直接退到后台
                gatePassed = false
                moveTaskToBack(true)
            }
        }
    }

    /**
     * 申请存储权限：把配置备份写到 /sdcard/KidLock，
     * 这样即使卸载重装也能自动恢复（拒绝则退回应用专属外部目录，功能不受影响）。
     */
    private fun requestStoragePermissionIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT < 23) return
            val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    REQ_STORAGE
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "request storage permission failed", t)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "getConfig" -> result.success(ConfigStore.load(this).toMap())

                        "saveConfig" -> {
                            @Suppress("UNCHECKED_CAST")
                            val args = call.arguments as? Map<*, *>
                            if (args == null) {
                                result.error("ERR", "empty args", null)
                            } else {
                                val old = ConfigStore.load(this)
                                val cfg = LockConfig.fromMap(args)
                                ConfigStore.save(this, cfg)
                                // 端口变了要重启 Web 服务，否则只需重新评估一次规则
                                MonitorService.act(
                                    this,
                                    if (cfg.port != old.port) MonitorService.ACTION_RESTART_WEB
                                    else MonitorService.ACTION_EVAL
                                )
                                result.success(ConfigStore.load(this).toMap())
                            }
                        }

                        "getStatus" -> result.success(MonitorService.statusMap(this))

                        "startService" -> {
                            MonitorService.start(this)
                            result.success(MonitorService.isRunning(this))
                        }

                        "unlockOnce" -> {
                            MonitorService.grantSingleUnlock(this)
                            result.success(true)
                        }

                        "clearUnlock" -> {
                            MonitorService.act(this, MonitorService.ACTION_CLEAR_UNLOCK)
                            result.success(true)
                        }

                        "lockNow" -> {
                            MonitorService.act(this, MonitorService.ACTION_LOCK_NOW)
                            result.success(true)
                        }

                        "restartWeb" -> {
                            MonitorService.act(this, MonitorService.ACTION_RESTART_WEB)
                            result.success(true)
                        }

                        "backupConfig" -> {
                            result.success(ConfigStore.backupNow(this) ?: "")
                        }

                        "openUsageAccess" -> {
                            startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            result.success(true)
                        }

                        "activateDeviceAdmin" -> {
                            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, KidDeviceAdmin.cmp(this))
                            i.putExtra(
                                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                getString(R.string.admin_explain)
                            )
                            startActivity(i)
                            result.success(true)
                        }

                        else -> result.notImplemented()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "channel ${call.method} failed", t)
                    result.error("ERR", t.message, null)
                }
            }
    }

    override fun onPause() {
        super.onPause()
        lastPauseAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台都确保守护服务在跑
        MonitorService.start(this)
        // 离开 App 较久后回来（或首次进入）需要重新做家长验证
        if (lastPauseAt > 0L && System.currentTimeMillis() - lastPauseAt > REGATE_AFTER_MS) {
            gatePassed = false
        }
        ensureGate()
    }
}
