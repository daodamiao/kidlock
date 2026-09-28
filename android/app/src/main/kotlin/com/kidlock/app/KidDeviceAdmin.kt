package com.kidlock.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备管理器：
 *  1) 激活后必须先取消激活才能卸载 —— 防止孩子直接卸载 App
 *  2) 提供 lockNow() 能力（配置里“到点黑屏锁屏”打开时使用）
 */
class KidDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "device admin disabled")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        Log.w(TAG, "password failed")
    }

    companion object {
        private const val TAG = "KidLock.Admin"

        fun cmp(c: Context): ComponentName = ComponentName(c, KidDeviceAdmin::class.java)

        fun isActive(c: Context): Boolean {
            return try {
                val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                dpm?.isAdminActive(cmp(c)) ?: false
            } catch (t: Throwable) {
                false
            }
        }
    }
}
