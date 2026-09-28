package com.kidlock.app

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * 配置持久化（双重保险）：
 *  1) SharedPreferences 存一份 JSON —— 覆盖安装/升级不会丢
 *  2) 外部存储再写一份备份文件 —— 即使卸载重装也能自动恢复
 *     · 优先 /sdcard/KidLock/kidlock_config.json（需存储权限，卸载后仍保留）
 *     · 无权限时退回应用专属外部目录（无需权限，卸载会删除）
 * 规则只依赖本地存储 + 系统时间 —— 断网、重启都不影响规则生效。
 */
object ConfigStore {
    private const val TAG = "KidLock.Cfg"
    private const val PREF = "kidlock"
    private const val K_CONFIG = "config"
    private const val K_UNLOCK_UNTIL = "unlock_until"
    private const val K_MANUAL_LOCK = "manual_lock"

    private const val BACKUP_FILE = "kidlock_config.json"
    private const val PUBLIC_DIR = "KidLock"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 备份文件

    /** 备份候选路径：先公共目录（卸载后仍在），再应用专属外部目录（无需权限） */
    private fun backupCandidates(c: Context): List<File> {
        val list = ArrayList<File>()
        try {
            val pub = File(Environment.getExternalStorageDirectory(), PUBLIC_DIR)
            list.add(File(pub, BACKUP_FILE))
        } catch (t: Throwable) {
            Log.w(TAG, "public dir unavailable", t)
        }
        try {
            c.applicationContext.getExternalFilesDir(null)?.let { list.add(File(it, BACKUP_FILE)) }
        } catch (t: Throwable) {
            Log.w(TAG, "external files dir unavailable", t)
        }
        return list
    }

    /** 写入外部备份，能写哪个写哪个；失败不影响主流程 */
    private fun writeBackup(c: Context, json: String) {
        for (f in backupCandidates(c)) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(json)
                Log.i(TAG, "config backup written: ${f.absolutePath}")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "config backup write failed: ${f.absolutePath}", t)
            }
        }
    }

    private fun readBackup(c: Context): String? {
        for (f in backupCandidates(c)) {
            try {
                if (f.exists() && f.length() > 0L) {
                    val text = f.readText()
                    if (text.trimStart().startsWith("{")) {
                        Log.i(TAG, "config restored from backup: ${f.absolutePath}")
                        return text
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "config backup read failed: ${f.absolutePath}", t)
            }
        }
        return null
    }

    /** 已生效的备份文件路径（用于状态展示），无则返回 null */
    fun backupPath(c: Context): String? {
        for (f in backupCandidates(c)) {
            try {
                if (f.exists() && f.length() > 0L) return f.absolutePath
            } catch (t: Throwable) {
                // ignore
            }
        }
        return null
    }

    /** 手动触发一次备份（Web 控制台「立即备份配置」按钮） */
    @Synchronized
    fun backupNow(c: Context): String? {
        val cfg = load(c)
        writeBackup(c, cfg.toJson().toString())
        return backupPath(c)
    }

    // ------------------------------------------------------------ 读写配置

    @Synchronized
    fun load(c: Context): LockConfig {
        val raw = prefs(c).getString(K_CONFIG, null)

        if (raw != null) {
            val cfg = try {
                org.json.JSONObject(raw).let { LockConfig.fromJson(it) }
            } catch (t: Throwable) {
                Log.e(TAG, "load config failed, use default", t)
                return LockConfig.defaultConfig()
            }
            // 迁移：8080 为旧默认端口，容易与盒子系统服务冲突（返回 Not Found），统一切到 6666
            if (cfg.port == 8080) {
                cfg.port = 6666
                save(c, cfg)
            } else if (backupPath(c) == null) {
                // 老版本升级上来、还没有外部备份时，补写一份
                writeBackup(c, cfg.toJson().toString())
            }
            return cfg
        }

        // 首选项为空（首次安装 / 卸载重装）：尝试从外部备份自动恢复
        val bk = readBackup(c)
        if (bk != null) {
            try {
                val cfg = org.json.JSONObject(bk).let { LockConfig.fromJson(it) }
                prefs(c).edit().putString(K_CONFIG, cfg.toJson().toString()).apply()
                Log.i(TAG, "config restored and persisted")
                return cfg
            } catch (t: Throwable) {
                Log.e(TAG, "restore from backup failed", t)
            }
        }

        return LockConfig.defaultConfig()
    }

    @Synchronized
    fun save(c: Context, cfg: LockConfig) {
        try {
            val json = cfg.toJson().toString()
            prefs(c).edit().putString(K_CONFIG, json).apply()
            writeBackup(c, json)
        } catch (t: Throwable) {
            Log.e(TAG, "save config failed", t)
        }
    }

    /** 单次解锁截止时间戳（毫秒），0 = 无 */
    fun getUnlockUntil(c: Context): Long = prefs(c).getLong(K_UNLOCK_UNTIL, 0L)

    fun setUnlockUntil(c: Context, ts: Long) {
        prefs(c).edit().putLong(K_UNLOCK_UNTIL, ts).apply()
    }

    /** Web / App 端“立即锁定”的手动锁定标记 */
    fun getManualLock(c: Context): Boolean = prefs(c).getBoolean(K_MANUAL_LOCK, false)

    fun setManualLock(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(K_MANUAL_LOCK, v).apply()
    }
}
