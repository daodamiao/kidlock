package com.kidlock.app

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * 前台应用识别。
 * 主路径：UsageStatsManager（需用户授权“使用情况访问”，可用 adb appops 授权）；
 * 降级路径：ActivityManager 进程重要性 / RunningTasks。
 * 任一路径失败都不影响主流程（只是“杀进程”这一步精度下降）。
 */
object ForegroundHelper {
    private const val TAG = "KidLock.Fg"
    private const val WINDOW_MS = 60L * 1000L

    fun hasUsageAccess(c: Context): Boolean {
        return try {
            val ao = c.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = ao.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }

    fun getForegroundPackage(c: Context): String? {
        if (hasUsageAccess(c)) {
            try {
                val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                val now = System.currentTimeMillis()
                val stats = usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - WINDOW_MS, now)
                var best: String? = null
                var last = 0L
                if (stats != null) {
                    for (s in stats) {
                        val t = s.lastTimeUsed
                        if (t > last) {
                            last = t
                            best = s.packageName
                        }
                    }
                }
                if (best != null && now - last < WINDOW_MS) return best
            } catch (t: Throwable) {
                Log.w(TAG, "usage stats failed", t)
            }
        }
        return fallback(c)
    }

    private fun fallback(c: Context): String? {
        return try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val ps = am.runningAppProcesses
            if (ps != null) {
                for (p in ps) {
                    if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        p.pkgList != null && p.pkgList.isNotEmpty()
                    ) {
                        return p.pkgList[0]
                    }
                }
            }
            val tasks = am.getRunningTasks(5)
            if (tasks != null && tasks.isNotEmpty()) {
                return tasks[0].topActivity?.packageName
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "fallback failed", t)
            null
        }
    }
}
