package com.kidlock.app

import android.app.Application
import android.util.Log

/**
 * 进程一启动就拉起守护服务：
 * 开机广播、Flutter 页面、Web 保存配置等任一入口进入应用进程时都会走到这里。
 */
class KidLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            MonitorService.start(this)
            MonitorService.scheduleHeartbeat(this)
        } catch (t: Throwable) {
            Log.e("KidLock.App", "bootstrap failed", t)
        }
    }
}
