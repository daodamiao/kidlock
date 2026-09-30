package com.kidlock.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * 屏幕截图授权页（无界面）。
 *
 * Web 端点击「查看当前画面」时，若尚未授权，服务会拉起本页，
 * 由系统弹出「是否开始截取屏幕」对话框，家长在电视上用遥控器点「允许」即可。
 * 授权结果写入 [ScreenCapture]，之后本进程内截图不再弹窗。
 */
class CaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 授权期间让守护服务暂缓抢占前台，否则系统授权框会被锁屏页顶掉
        ScreenCapture.markConsentUiActive()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpm == null) {
            Log.w(TAG, "MediaProjectionManager unavailable")
            ScreenCapture.clearConsentUi()
            finish()
            return
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CONSENT)
        } catch (t: Throwable) {
            Log.w(TAG, "start consent failed", t)
            ScreenCapture.clearConsentUi()
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONSENT) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenCapture.storeResult(resultCode, data)
                Log.i(TAG, "screen capture consent granted")
            } else {
                Log.i(TAG, "screen capture consent denied")
            }
        }
        ScreenCapture.clearConsentUi()
        finish()
    }

    override fun onDestroy() {
        ScreenCapture.clearConsentUi()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KidLock.ShotAct"
        private const val REQ_CONSENT = 2101

        /** 拉起授权页（调用方可能是 Service，必须带 NEW_TASK） */
        @JvmStatic
        fun start(c: Context) {
            try {
                val i = Intent(c, CaptureActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                c.startActivity(i)
            } catch (t: Throwable) {
                Log.w(TAG, "start failed", t)
            }
        }
    }
}
