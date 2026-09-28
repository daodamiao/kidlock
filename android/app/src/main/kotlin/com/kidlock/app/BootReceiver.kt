package com.kidlock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 升级 / 时间变更 / 时区变更后重新拉起守护服务。
 * targetSdk=25，隐式广播不受 Android 8+ 限制，静态注册即可生效。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent?) {
        Log.i("KidLock.Boot", "receive ${intent?.action}")
        try {
            MonitorService.start(c)
            MonitorService.scheduleHeartbeat(c)
        } catch (t: Throwable) {
            Log.e("KidLock.Boot", "start failed", t)
        }
    }
}
