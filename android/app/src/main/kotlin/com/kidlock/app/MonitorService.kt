package com.kidlock.app

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 常驻管控服务（前台服务 + START_STICKY）：
 *  1. 10s 轮询规则；同时在下一个翻转点注册精确闹钟（双保险）
 *  2. 到点：暂停媒体 -> 弹全屏锁屏抢占前台 -> 杀掉原前台应用进程
 *  3. 未到点：若锁屏仍在显示则关闭
 *  4. 内嵌 Web 配置服务随服务一起存活
 *  5. 心跳闹钟 + onDestroy/onTaskRemoved 自拉活
 */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var web: ConfigWebServer? = null
    private var lastShowAttempt = 0L
    private var lastKill = 0L
    private var lastWebAttempt = 0L
    private var started = false

    private val ticker = object : Runnable {
        override fun run() {
            try {
                evaluate()
            } catch (t: Throwable) {
                Log.e(TAG, "tick error", t)
            }
            handler.removeCallbacks(this)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        started = true
        startForeground(NOTIFY_ID, buildNotification())
        handler.post(ticker)
        scheduleHeartbeat(this)
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val a = intent?.action ?: ACTION_START
        Log.i(TAG, "onStartCommand $a")
        when (a) {
            ACTION_RESTART_WEB -> restartWeb()
            ACTION_UNLOCK_ONCE -> grantSingleUnlock(this)
            ACTION_LOCK_NOW -> {
                ConfigStore.setUnlockUntil(this, 0L)
                ConfigStore.setManualLock(this, true)
            }
            ACTION_CLEAR_UNLOCK -> {
                ConfigStore.setUnlockUntil(this, 0L)
                ConfigStore.setManualLock(this, false)
            }
            ACTION_BACKUP -> {
                val p = ConfigStore.backupNow(this)
                Log.i(TAG, "manual backup -> $p")
            }
        }
        try {
            evaluate()
        } catch (t: Throwable) {
            Log.e(TAG, "evaluate error", t)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleRestart(this, 2000L)
    }

    override fun onDestroy() {
        started = false
        handler.removeCallbacks(ticker)
        try {
            web?.stop()
        } catch (t: Throwable) {
            // ignore
        }
        web = null
        scheduleRestart(this, 3000L)
        Log.w(TAG, "service destroyed, restart scheduled")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 核心评估

    private fun evaluate() {
        val cfg = ConfigStore.load(this)
        val now = System.currentTimeMillis()
        var unlockUntil = ConfigStore.getUnlockUntil(this)
        val manual = ConfigStore.getManualLock(this)

        if (unlockUntil > 0L && now >= unlockUntil) {
            ConfigStore.setUnlockUntil(this, 0L)
            unlockUntil = 0L
        }
        val locked = TimeRule.isLocked(cfg, now, unlockUntil, manual)

        if (locked) {
            enforceLock(now, cfg)
        } else {
            LockScreenActivity.requestFinish()
        }

        scheduleNext(cfg, now, unlockUntil)
        ensureWeb(cfg, now)
    }

    private fun enforceLock(now: Long, cfg: LockConfig) {
        if (!LockScreenActivity.isVisible()) {
            if (now - lastShowAttempt >= SHOW_RETRY_MS) {
                lastShowAttempt = now
                if (cfg.forceStop) hardStopForegroundApp()
                showLock()
                if (cfg.lockNow) lockScreenNow()
            }
        } else if (cfg.forceStop && now - lastKill >= KILL_INTERVAL_MS) {
            // 看门狗：锁屏已显示，但仍有别的应用抢到前台（被通知 / 定时器拉起）
            lastKill = now
            val p = ForegroundHelper.getForegroundPackage(this)
            if (p != null && p != packageName && p != "android" && p != "com.android.systemui") {
                killPackage(p)
            }
        }
    }

    private fun showLock() {
        try {
            val it = Intent(this, LockScreenActivity::class.java)
            it.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            startActivity(it)
        } catch (t: Throwable) {
            Log.e(TAG, "showLock failed", t)
        }
    }

    /** 到点“强制停止当前播放”：先发暂停键，再把（已退到后台的）应用进程杀掉 */
    private fun hardStopForegroundApp() {
        pauseMedia()
        val pkg = ForegroundHelper.getForegroundPackage(this)
        if (pkg == null || pkg == packageName || pkg == "android") return
        handler.postDelayed({ killPackage(pkg) }, 300L)
    }

    private fun pauseMedia() {
        // 1) 走 AudioManager 媒体键（用反射，避免不同固件上 API 差异导致崩溃）
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val t = SystemClock.uptimeMillis()
            val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            val up = KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            val m = am.javaClass.getMethod("dispatchMediaKeyEvent", KeyEvent::class.java)
            m.invoke(am, down)
            m.invoke(am, up)
        } catch (t: Throwable) {
            Log.w(TAG, "media key pause failed", t)
        }
        // 2) 兼容常见播放器的通用暂停广播（无需权限）
        try {
            val i = Intent("com.android.music.musicservicecommand")
            i.putExtra("command", "pause")
            sendBroadcast(i)
        } catch (t: Throwable) {
            Log.w(TAG, "pause broadcast failed", t)
        }
    }

    private fun killPackage(pkg: String) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses(pkg)
            Log.i(TAG, "killed $pkg")
        } catch (t: Throwable) {
            Log.w(TAG, "kill failed", t)
        }
    }

    private fun lockScreenNow() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm != null && dpm.isAdminActive(KidDeviceAdmin.cmp(this))) {
                dpm.lockNow()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "lockNow failed", t)
        }
    }

    // ------------------------------------------------------------------ Web 控制台

    private fun ensureWeb(cfg: LockConfig, now: Long) {
        try {
            if (web == null) web = ConfigWebServer(this)
            val w = web ?: return
            if (w.isRunning && w.port == cfg.port) return
            if (now - lastWebAttempt < 10000L) return
            lastWebAttempt = now
            w.start(cfg.port)
        } catch (t: Throwable) {
            Log.e(TAG, "web start failed", t)
        }
    }

    private fun restartWeb() {
        try {
            web?.stop()
            val cfg = ConfigStore.load(this)
            web = ConfigWebServer(this).apply { start(cfg.port) }
        } catch (t: Throwable) {
            Log.e(TAG, "restartWeb failed", t)
        }
    }

    // ------------------------------------------------------------------ 定时 / 自保活

    private fun scheduleNext(cfg: LockConfig, now: Long, unlockUntil: Long) {
        val next = TimeRule.nextTransitionAfter(cfg, now, unlockUntil)
        if (next == Long.MAX_VALUE) return
        val trigger = Math.max(now + 5000L, next + 1000L)
        try {
            val am = getSystemService(ALARM_SERVICE) as? AlarmManager ?: return
            val pi = evalPendingIntent(this)
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scheduleNext failed", t)
        }
    }

    private fun buildNotification(): Notification {
        val b = Notification.Builder(this)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notify_running))
            .setOngoing(true)
            .setWhen(System.currentTimeMillis())
        val it = Intent(this, MainActivity::class.java)
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        b.setContentIntent(PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT))
        return b.build()
    }

    companion object {
        private const val TAG = "KidLock.Svc"

        const val ACTION_START = "com.kidlock.app.action.START"
        const val ACTION_EVAL = "com.kidlock.app.action.EVAL"
        const val ACTION_UNLOCK_ONCE = "com.kidlock.app.action.UNLOCK_ONCE"
        const val ACTION_LOCK_NOW = "com.kidlock.app.action.LOCK_NOW"
        const val ACTION_CLEAR_UNLOCK = "com.kidlock.app.action.CLEAR_UNLOCK"
        const val ACTION_RESTART_WEB = "com.kidlock.app.action.RESTART_WEB"
        const val ACTION_BACKUP = "com.kidlock.app.action.BACKUP"

        private const val NOTIFY_ID = 20171
        private const val TICK_MS = 10000L
        private const val HEARTBEAT_MS = 10L * 60L * 1000L
        private const val SHOW_RETRY_MS = 3000L
        private const val KILL_INTERVAL_MS = 20000L

        @Volatile
        private var lastInstance: MonitorService? = null

        private fun evalPendingIntent(c: Context): PendingIntent {
            val i = Intent(c, MonitorService::class.java).setAction(ACTION_EVAL)
            return PendingIntent.getService(c, 2001, i, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** 启动（或唤醒）守护服务 */
        @JvmStatic
        fun start(c: Context) {
            try {
                val i = Intent(c, MonitorService::class.java).setAction(ACTION_START)
                c.startService(i)
            } catch (t: Throwable) {
                Log.e(TAG, "startService failed", t)
            }
        }

        @JvmStatic
        fun act(c: Context, action: String) {
            try {
                val i = Intent(c, MonitorService::class.java).setAction(action)
                c.startService(i)
            } catch (t: Throwable) {
                Log.e(TAG, "action $action failed", t)
            }
        }

        @JvmStatic
        fun scheduleHeartbeat(c: Context) {
            try {
                val am = c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val i = Intent(c, MonitorService::class.java).setAction(ACTION_START)
                val pi = PendingIntent.getService(c, 2002, i, PendingIntent.FLAG_UPDATE_CURRENT)
                am.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 60000L, HEARTBEAT_MS, pi
                )
            } catch (t: Throwable) {
                Log.w(TAG, "heartbeat failed", t)
            }
        }

        private fun scheduleRestart(c: Context, delayMs: Long) {
            try {
                val am = c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val i = Intent(c, MonitorService::class.java).setAction(ACTION_START)
                val pi = PendingIntent.getService(c, 2003, i, PendingIntent.FLAG_UPDATE_CURRENT)
                val at = System.currentTimeMillis() + delayMs
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "scheduleRestart failed", t)
            }
        }

        /**
         * 单次解锁：持续 min(单次解锁时长, 本次锁定的自然结束时刻)。
         * 这样既保证到下一个禁用时间点会重新锁定，也保证解锁时长不会“溢出”到下一段开放时间。
         */
        @JvmStatic
        fun grantSingleUnlock(c: Context) {
            val cfg = ConfigStore.load(c)
            val now = System.currentTimeMillis()
            var end = now + Math.max(1, cfg.singleUnlockMinutes) * 60000L
            val naturalUnlock = TimeRule.nextStateChange(cfg, now, false)
            if (naturalUnlock < end) {
                end = Math.max(now + 60000L, naturalUnlock)
            }
            ConfigStore.setUnlockUntil(c, end)
            ConfigStore.setManualLock(c, false)
            act(c, ACTION_EVAL)
            Log.i(TAG, "single unlock until " + TimeRule.stamp(end))
        }

        @JvmStatic
        fun isRunning(c: Context): Boolean {
            return try {
                val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
                for (s in am.getRunningServices(50)) {
                    if (MonitorService::class.java.name == s.service.className) return true
                }
                false
            } catch (t: Throwable) {
                false
            }
        }

        /** 供 Flutter 端 / Web 端展示的完整状态快照 */
        @JvmStatic
        fun statusMap(c: Context): Map<String, Any?> {
            val cfg = ConfigStore.load(c)
            val now = System.currentTimeMillis()
            val unlockUntil = ConfigStore.getUnlockUntil(c)
            val manual = ConfigStore.getManualLock(c)
            val locked = TimeRule.isLocked(cfg, now, unlockUntil, manual)
            val next = TimeRule.nextTransitionAfter(cfg, now, unlockUntil)
            val m = HashMap<String, Any?>()
            m["locked"] = locked
            m["enabled"] = cfg.enabled
            m["mode"] = cfg.mode
            m["now"] = now
            m["nowText"] = TimeRule.stamp(now)
            m["unlockUntil"] = unlockUntil
            m["unlockUntilText"] = TimeRule.stamp(unlockUntil)
            m["manualLock"] = manual
            m["nextTransition"] = if (next == Long.MAX_VALUE) 0L else next
            m["nextTransitionText"] = TimeRule.stamp(next)
            m["lockVisible"] = LockScreenActivity.isVisible()
            m["foreground"] = ForegroundHelper.getForegroundPackage(c) ?: ""
            m["usageAccess"] = ForegroundHelper.hasUsageAccess(c)
            m["deviceAdmin"] = KidDeviceAdmin.isActive(c)
            m["serviceRunning"] = isRunning(c)
            m["webRunning"] = ConfigWebServer.isAnyRunning()
            m["backup"] = ConfigStore.backupPath(c) ?: ""
            m["ips"] = localIps()
            m["port"] = cfg.port
            m["singleUnlockMinutes"] = cfg.singleUnlockMinutes
            m["segmentsText"] = cfg.segments.joinToString(" / ") {
                "${TimeRule.hhmm(it.start)}-${TimeRule.hhmm(it.end)}"
            }
            return m
        }

        /** 本机局域网 IPv4 地址列表 */
        @JvmStatic
        fun localIps(): List<String> {
            val out = ArrayList<String>()
            return try {
                val en = NetworkInterface.getNetworkInterfaces() ?: return out
                for (ni in Collections.list(en)) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (addr in Collections.list(ni.inetAddresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val s = addr.hostAddress ?: continue
                            if (s.isNotEmpty()) out.add(s)
                        }
                    }
                }
                out
            } catch (t: Throwable) {
                out
            }
        }
    }
}
