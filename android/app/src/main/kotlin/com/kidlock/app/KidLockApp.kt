package com.kidlock.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.lang.ref.WeakReference

/**
 * 进程一启动就拉起守护服务：
 * 开机广播、Flutter 页面、Web 保存配置等任一入口进入应用进程时都会走到这里。
 *
 * v1.4.3：额外跟踪当前处于 onResumed 的自家 Activity，供 Web 遥控器把按键
 * 直接投递到前台窗口（见 KeyInjector.dispatchToSelf）。用 WeakReference + onPause
 * 清空，避免持有已销毁 Activity 造成泄漏。
 */
class KidLockApp : Application() {

    companion object {
        private const val TAG = "KidLock.App"

        @Volatile
        private var resumedRef: WeakReference<Activity>? = null

        /** 当前前台的自家 Activity；为 null 表示前台是桌面或第三方应用。 */
        val foregroundActivity: Activity?
            get() = resumedRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

        internal fun markResumed(a: Activity) {
            resumedRef = WeakReference(a)
        }

        internal fun markPaused(a: Activity) {
            if (resumedRef?.get() === a) resumedRef = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) { KidLockApp.markResumed(a) }
            override fun onActivityPaused(a: Activity) { KidLockApp.markPaused(a) }
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { KidLockApp.markPaused(a) }
        })
        try {
            MonitorService.start(this)
            MonitorService.scheduleHeartbeat(this)
        } catch (t: Throwable) {
            Log.e(TAG, "bootstrap failed", t)
        }
    }
}
