package com.kidlock.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 全屏锁屏页（原生 Activity，启动最快、最省内存）：
 *  - 沉浸式全屏、常亮、可越过键盘锁显示
 *  - dispatchKeyEvent 吞掉遥控器所有按键（方向键 / 确认 / 返回 / 数字 / 音量）
 *  - 命中配置的按键序列 -> 单次解锁
 */
class LockScreenActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private var matcher = KeySequenceMatcher()
    private lateinit var tvClock: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvHint: TextView

    private val refresher = object : Runnable {
        override fun run() {
            try {
                updateUi()
            } catch (t: Throwable) {
                Log.e(TAG, "refresh error", t)
            }
            ui.removeCallbacks(this)
            ui.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val w = window
        w.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        hideSystemUi()
        setContentView(buildUi())

        val cfg = ConfigStore.load(this)
        matcher.setSequences(
            listOf(
                cfg.unlockKeys.toIntArray(),
                cfg.unlockKeys2.toIntArray(),
            )
        )

        // 若此刻已解锁（例如 Web 端刚点了临时解锁），直接退出
        if (!TimeRule.isLocked(
                cfg, System.currentTimeMillis(),
                ConfigStore.getUnlockUntil(this), ConfigStore.getManualLock(this)
            )
        ) {
            finish()
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1220"))
            setPadding(dp(48f), dp(32f), dp(48f), dp(32f))
        }

        val title = TextView(this).apply {
            text = getString(R.string.lock_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8f))
        }
        val sub = TextView(this).apply {
            text = getString(R.string.lock_subtitle)
            setTextColor(Color.parseColor("#93A1B5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }
        tvClock = TextView(this).apply {
            setTextColor(Color.parseColor("#4DA3FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 60f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, 0)
        }
        tvCountdown = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, 0)
        }
        tvHint = TextView(this).apply {
            setTextColor(Color.parseColor("#93A1B5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(8f))
        }

        root.addView(title)
        root.addView(sub)
        root.addView(tvClock)
        root.addView(tvCountdown)
        root.addView(tvHint)
        return root
    }

    private fun hideSystemUi() {
        val decor = window.decorView
        decor.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    override fun onResume() {
        super.onResume()
        instance = this
        lastVisibleAt = System.currentTimeMillis()
        hideSystemUi()
        updateUi()
        ui.removeCallbacks(refresher)
        ui.postDelayed(refresher, 1000L)
    }

    override fun onPause() {
        super.onPause()
        // 不清 instance：服务据此判断“锁屏是否仍在最上层”
    }

    override fun onDestroy() {
        ui.removeCallbacks(refresher)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        // 吞掉返回键
    }

    /** 吞掉遥控器所有按键，只识别解锁序列（不显示任何进度提示） */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val hit = try {
                matcher.feed(event.keyCode, event.eventTime)
            } catch (t: Throwable) {
                false
            }
            if (hit) {
                onUnlockSequence()
            }
        }
        return true
    }

    private fun onUnlockSequence() {
        MonitorService.grantSingleUnlock(this)
        val cfg = ConfigStore.load(this)
        Toast.makeText(this, getString(R.string.unlock_toast, cfg.singleUnlockMinutes), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun updateUi() {
        val cfg = ConfigStore.load(this)
        val now = System.currentTimeMillis()
        val unlockUntil = ConfigStore.getUnlockUntil(this)
        val manual = ConfigStore.getManualLock(this)

        if (!TimeRule.isLocked(cfg, now, unlockUntil, manual)) {
            finish()
            return
        }

        tvClock.text = TimeRule.clock(now)
        val nextUnlock = TimeRule.nextStateChange(cfg, now, false)
        tvCountdown.text = if (nextUnlock == Long.MAX_VALUE) {
            getString(R.string.countdown_unknown)
        } else {
            getString(R.string.countdown_format, TimeRule.dur(nextUnlock - now), TimeRule.stamp(nextUnlock))
        }
        tvHint.text = getString(R.string.hint_unlock, cfg.singleUnlockMinutes)
        lastVisibleAt = now
    }

    companion object {
        private const val TAG = "KidLock.Lock"
        private const val VISIBLE_WINDOW_MS = 8000L

        @Volatile
        private var instance: LockScreenActivity? = null

        @Volatile
        private var lastVisibleAt = 0L

        /** 锁屏当前是否还在最上层（服务据此决定要不要重新弹） */
        @JvmStatic
        fun isVisible(): Boolean {
            val a = instance ?: return false
            if (a.isFinishing) return false
            return System.currentTimeMillis() - lastVisibleAt < VISIBLE_WINDOW_MS
        }

        @JvmStatic
        fun requestFinish() {
            val a = instance ?: return
            a.ui.post {
                try {
                    a.finish()
                } catch (t: Throwable) {
                    // ignore
                }
            }
        }
    }
}
