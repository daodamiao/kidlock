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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissionIfNeeded()
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

    override fun onResume() {
        super.onResume()
        // 每次回到前台都确保守护服务在跑
        MonitorService.start(this)
    }
}
