package com.kidlock.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 家长验证页（打开 App 前的“门禁”）：
 *  - 输入任一已配置的解锁序列（电视遥控器方向键 / 手机音量键）才能进入 App 界面
 *  - 吞掉所有按键；验证成功写入放行时间戳，超时后再次进入仍需验证
 *  - 家长按遥控器 HOME 键可退出 App（HOME 由系统处理，App 无法拦截）
 */
class GateActivity : Activity() {

    private var matcher = KeySequenceMatcher()
    private lateinit var tvTip: TextView
    private var wrongCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        val cfg = ConfigStore.load(this)
        matcher.setSequences(
            listOf(
                cfg.unlockKeys.toIntArray(),
                cfg.unlockKeys2.toIntArray(),
            )
        )
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
            text = "家长验证"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = "请输入家长解锁序列后继续\n（遥控器方向键，或手机音量键）"
            setTextColor(Color.parseColor("#93A1B5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setPadding(0, dp(14f), 0, 0)
        }
        tvTip = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#FF8A76"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, dp(18f), 0, 0)
        }
        root.addView(title)
        root.addView(sub)
        root.addView(tvTip)
        return root
    }

    /** 吞掉所有按键，只识别解锁序列 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val hit = try {
                matcher.feed(event.keyCode, event.eventTime)
            } catch (t: Throwable) {
                Log.w(TAG, "feed failed", t)
                false
            }
            if (hit) {
                ConfigStore.setGateUntil(this, System.currentTimeMillis() + GATE_TTL_MS)
                setResult(RESULT_OK)
                finish()
            } else if (matcher.progress() == 0) {
                // 只有“完全偏离”的按键才记为一次错误，避免输入中途误提示
                if (++wrongCount % 5 == 0) {
                    tvTip.text = "序列不正确，请重新输入"
                }
            }
        }
        return true
    }

    override fun onBackPressed() {
        // 吞掉返回键，避免儿童直接进入
    }

    companion object {
        private const val TAG = "KidLock.Gate"

        /** 验证通过后的放行时长：超过该时间（或重新打开 App）需再次验证 */
        const val GATE_TTL_MS = 30_000L
    }
}
