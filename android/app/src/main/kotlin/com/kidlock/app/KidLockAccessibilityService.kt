package com.kidlock.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * 无 root 设备上让 Web 遥控器的「返回」真正生效的正规通道。
 *
 * INJECT_EVENTS 是 signature 级权限，普通应用拿不到，因此应用进程自己注入 BACK 会失败；
 * 而 AccessibilityService.performGlobalAction(GLOBAL_ACTION_BACK) 由系统代为执行全局返回，
 * 不需要注入权限，也不受固件对注入事件的拦截影响。
 *
 * 隐私：不读取窗口内容（canRetrieveWindowContent=false），收到的事件一律忽略，
 * 只在家长通过 Web 遥控器点击「返回」时才主动调用一次 performGlobalAction。
 * 需用户在 设置 → 无障碍 中手动开启一次，前端会显示状态并提供跳转按钮。
 */
class KidLockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KidLock.A11y"

        @Volatile
        private var ref: WeakReference<KidLockAccessibilityService>? = null

        /** 服务是否已被系统绑定（用户在无障碍设置里开启后才会为 true）。 */
        val isConnected: Boolean
            get() = ref?.get() != null

        /** 执行一次全局返回；未开启或调用失败返回 false，由 KeyInjector 继续降级。 */
        fun performBack(): Boolean {
            val s = ref?.get() ?: return false
            return try {
                val ok = s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Log.i(TAG, "performGlobalAction(BACK) = $ok")
                ok
            } catch (t: Throwable) {
                Log.w(TAG, "performGlobalAction failed", t)
                false
            }
        }

        /** 读取系统「已启用的无障碍服务」列表，用于 Web 页面显示开启状态。 */
        fun isEnabledInSettings(c: Context): Boolean = try {
            val raw = Settings.Secure.getString(
                c.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val full = c.packageName + "/" + KidLockAccessibilityService::class.java.name
            val short = c.packageName + "/.KidLockAccessibilityService"
            raw.split(':').any { it.equals(full, true) || it.equals(short, true) }
        } catch (t: Throwable) {
            false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ref = WeakReference(this)
        Log.i(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不监听、不读取、不上传任何窗口事件
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ref = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ref = null
        super.onDestroy()
    }
}
