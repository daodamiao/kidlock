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
 *  2. 到点：静音（媒体键 + 暂停广播 + 音频焦点抢占）-> 弹全屏锁屏抢占前台
 *     -> 等目标应用退到后台后杀进程，2.5 秒后再补一刀
 *  3. 未到点：若锁屏仍在显示则关闭
 *  4. 内嵌 Web 配置服务随服务一起存活
 *  5. 心跳闹钟 + onDestroy/onTaskRemoved 自拉活
 *
 *  说明：IPTV / 直播类应用常以前台服务常驻，且不响应媒体暂停键，
 *  因此“杀掉进程”不可靠，最终兜底是持续静音（muteAudio）。
 */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var web: ConfigWebServer? = null
    private var lastShowAttempt = 0L
    private var lastKill = 0L
    private var lastWebAttempt = 0L
    private var started = false

    /** 是否正处于“锁屏静音”状态，用于解锁后恢复音量 */
    private var wasQuiet = false

    /** 静音前的音乐流音量，解锁后恢复 */
    private var savedMusicVolume = -1

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
            // 解锁 / 进入开放时段：解除静音，并把音量恢复到原本的水平
            if (wasQuiet) {
                wasQuiet = false
                restoreVolume()
            }
            // 未锁定时持续记录当前前台应用，作为「锁屏前的应用」备选
            val fg = ForegroundHelper.getForegroundPackage(this)
            if (isThirdParty(fg)) rememberForeground(fg)
            LockScreenActivity.requestFinish()
        }

        scheduleNext(cfg, now, unlockUntil)
        ensureWeb(cfg, now)
    }

    /**
     * 执行锁定。到点“强制停止当前播放”的完整流程（IPTV / 直播类应用不会响应媒体暂停键，
     * 所以采用「静音兜底 + 多轮杀进程」策略，任一轮命中即可）：
     *   1. 记下当前前台应用（此时它还在前台，杀不掉，必须先记下来）
     *   2. 发媒体暂停键 + 各类播放器暂停广播
     *   3. 弹锁屏抢占前台（锁屏自身还会请求 AUDIOFOCUS_GAIN，逼播放器让出音频焦点）
     *   4. 0.8s 后杀目标进程（此时它已退到后台，killBackgroundProcesses 才有效）
     *   5. 1.2s / 2.5s 再各静音 / 补刀一次，覆盖响应慢的播放器
     * 兜底：即使进程始终没被回收，音乐流也被静音，听不到声音。
     */
    private fun enforceLock(now: Long, cfg: LockConfig) {
        if (!LockScreenActivity.isVisible()) {
            if (now - lastShowAttempt >= SHOW_RETRY_MS) {
                lastShowAttempt = now
                // 先记下当前前台应用（此刻它仍在最上层）：
                //  · 记入 lastForeground —— 锁屏期间界面显示「锁屏前的应用」
                //  · 作为杀进程目标 —— 只有等它退到后台，killBackgroundProcesses 才有效
                val fg = ForegroundHelper.getForegroundPackage(this)
                if (isThirdParty(fg)) rememberForeground(fg)
                val target = if (cfg.forceStop) fg else null
                if (cfg.forceStop) pauseMedia()
                // 家长正在电视上处理「截屏授权」时，不要把系统授权框顶掉
                if (!ScreenCapture.isConsentUiActive()) {
                    showLock()
                    // 弹锁屏会重新抢占音频焦点，因此静音与杀进程都放到锁屏起来之后
                    if (cfg.forceStop) {
                        handler.postDelayed({ muteAudio() }, QUIET_DELAY_MS)
                        handler.postDelayed({ killIfNeeded(target) }, KILL_DELAY_MS)
                        handler.postDelayed({ hardStopAgain() }, KILL_RETRY_MS)
                    }
                    if (cfg.lockNow) lockScreenNow()
                }
            }
        } else if (now - lastKill >= KILL_INTERVAL_MS && cfg.forceStop) {
            // 看门狗：锁屏已显示，但仍有别的应用抢到前台（被通知 / 定时器拉起 / 常驻服务）
            lastKill = now
            val p = ForegroundHelper.getForegroundPackage(this)
            if (p != null && isThirdParty(p)) {
                muteAudio()
                handler.postDelayed({ killPackage(p) }, KILL_DELAY_MS)
            } else {
                // 没有第三方前台：仍可能残留后台播放（IPTV 常以前台服务常驻），每轮补一次静音
                muteAudio()
            }
        }
    }

    /**
     * 杀掉指定应用（若它已退到后台）。
     * 传入 null 或目标已失效时，改为杀「当前前台」的第三方应用。
     * 若此刻目标仍是前台，说明还没降级，杀不掉是预期结果 —— 由静音兜底。
     */
    private fun killIfNeeded(target: String?) {
        try {
            if (!TimeRule.isLocked(
                    ConfigStore.load(this), System.currentTimeMillis(),
                    ConfigStore.getUnlockUntil(this), ConfigStore.getManualLock(this)
                )
            ) {
                return
            }
        } catch (t: Throwable) {
            // 状态判断失败也继续，锁屏自会按规则退出
        }
        var victim = target
        if (!isThirdParty(victim)) {
            // 目标不可用（未取到 / 是系统应用 / 是自己）：退而杀当前前台
            victim = ForegroundHelper.getForegroundPackage(this)
        }
        val v = victim
        if (v != null && isThirdParty(v)) {
            killPackage(v)
        }
        muteAudio()
    }

    /** 第一轮没停住时的追加补刀：再静音一次并重杀一次后台进程 */
    private fun hardStopAgain() {
        killIfNeeded(null)
    }

    private fun isThirdParty(pkg: String?): Boolean =
        pkg != null && pkg != packageName && pkg != "android" && pkg != "com.android.systemui"

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
        // 2) 兼容常见播放器（含 MX / VLC 等 IPTV 常用播放器）的暂停广播
        for (action in PAUSE_BROADCASTS) {
            try {
                val i = Intent(action)
                i.putExtra("command", "pause")
                sendBroadcast(i)
            } catch (t: Throwable) {
                Log.w(TAG, "pause broadcast $action failed", t)
            }
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

    /**
     * 请求音频焦点并立刻放弃：系统会把焦点转给下一位（通常是锁屏自身），
     * 正在播放的 IPTV / 直播应用会收到 AUDIOFOCUS_LOSS 从而暂停或降低音量。
     * 这是对媒体键不响应类播放器最有效的一招。
     */
    private fun muteAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            // 首次静音时记住原始音量，解锁后还原
            if (!wasQuiet) {
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (cur > 0) savedMusicVolume = cur
            }
            wasQuiet = true
            for (attempt in 0 until 2) {
                val res = am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    am.abandonAudioFocus(null)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "muteAudio failed", t)
        }
        // 部分机型的隐藏音量接口：直接静音音乐流（失败不影响其他手段）
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val cls = am.javaClass
            val m = cls.getMethod(
                "setStreamMute",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            m.invoke(am, AudioManager.STREAM_MUSIC, true)
        } catch (t: Throwable) {
            // 隐藏 API 在部分固件不可用，属预期情况，不记录噪音日志。
            // 退而求其次：直接把音乐流音量设为 0（需要勿扰权限，失败也无害）
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } catch (t2: Throwable) {
                // ignore
            }
        }
    }

    /** 解锁 / 开放时段恢复：取消静音并还原音量 */
    private fun restoreVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            try {
                val m = am.javaClass.getMethod(
                    "setStreamMute",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                m.invoke(am, AudioManager.STREAM_MUSIC, false)
            } catch (t: Throwable) {
                // ignore：部分固件无此接口
            }
            if (savedMusicVolume > 0) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                } catch (t: Throwable) {
                    // ignore
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restoreVolume failed", t)
        } finally {
            savedMusicVolume = -1
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

        /** 弹锁屏后等待应用退到后台的时间，再执行杀进程（前台进程杀不掉，必须先降级） */
        private const val KILL_DELAY_MS = 800L

        /** 第一轮补刀时间：留足时间让播放器响应暂停键 / 音频焦点变化 */
        private const val KILL_RETRY_MS = 2500L

        /** 弹锁屏后再次静音的时间点 */
        private const val QUIET_DELAY_MS = 1200L

        /** 各类播放器通用的暂停广播（IPTV / 本地播放器都有人用） */
        private val PAUSE_BROADCASTS = arrayOf(
            "com.android.music.musicservicecommand",
            "com.mxtech.videoplayer.ad.musicservicecommand",
            "com.mxtech.videoplayer.pro.musicservicecommand",
            "org.videolan.vlc.musicservicecommand",
            "com.android.music.metachanged"
        )

        /** 最近一次记录的前台应用（锁屏期间界面显示「锁屏前的应用」） */
        @Volatile
        private var lastForegroundPkg: String? = null

        /** 记录前台应用，供 statusMap 在锁屏期间展示 */
        @JvmStatic
        fun rememberForeground(pkg: String?) {
            if (!pkg.isNullOrEmpty()) lastForegroundPkg = pkg
        }

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
            // 是否正处于「临时解锁」状态（前端据此切换「临时解锁 / 清除临时解锁」按钮文案）
            m["tempUnlocked"] = unlockUntil > now
            m["manualLock"] = manual
            m["nextTransition"] = if (next == Long.MAX_VALUE) 0L else next
            m["nextTransitionText"] = TimeRule.stamp(next)
            m["lockVisible"] = LockScreenActivity.isVisible()
            // 锁屏中展示「锁屏前的应用」：锁屏页在最上层，此时取实时前台只会拿到自己，
            // 而真正该关心的是被暂停在后台的那个应用。
            val fgNow = ForegroundHelper.getForegroundPackage(c)
            val pre = lastForegroundPkg
            val usePre = locked && !pre.isNullOrEmpty()
            val fgShow = when {
                usePre -> pre
                !fgNow.isNullOrEmpty() && fgNow != c.packageName -> fgNow
                !pre.isNullOrEmpty() -> pre
                else -> fgNow ?: ""
            }
            m["foreground"] = fgShow
            m["foregroundName"] = ForegroundHelper.appLabel(c, fgShow)
            m["foregroundPreLock"] = usePre
            m["usageAccess"] = ForegroundHelper.hasUsageAccess(c)
            m["deviceAdmin"] = KidDeviceAdmin.isActive(c)
            m["serviceRunning"] = isRunning(c)
            m["webRunning"] = ConfigWebServer.isAnyRunning()
            m["backup"] = ConfigStore.backupPath(c) ?: ""
            m["ips"] = localIps()
            m["port"] = cfg.port
            m["singleUnlockMinutes"] = cfg.singleUnlockMinutes
            // 生效时间段：按星期分组，同组内的多段时间用「、」连接，组间换行
            m["segmentsText"] = TimeRule.describeSegments(cfg)
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
