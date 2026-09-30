package com.kidlock.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 屏幕截图（MediaProjection，免 root）。
 *
 * 授权模型：Android 要求首次截图前由用户在弹出的系统对话框上确认一次
 * （电视盒子可用遥控器点「允许」）。授权结果通过 [storeResult] 缓存，
 * 之后本进程内可反复截图，无需再次弹窗；进程被杀后需重新授权。
 *
 * 兼容性说明：
 *  · minSdk 24 已包含 MediaProjection（API 21+）；
 *  · targetSdk 25 → 不触发 Android 14+ 对 mediaProjection 前台服务类型的强校验；
 *  · 所有异常都被吞掉并降级为“截图失败”，绝不影响守护服务主流程。
 */
object ScreenCapture {

    private const val TAG = "KidLock.Shot"
    private const val VIRTUAL_DISPLAY_NAME = "kidlock-shot"
    private const val MAX_WIDTH = 1280          // 限制宽度，减小 JPEG 体积
    private const val JPEG_QUALITY = 80
    private const val FRAME_WAIT_MS = 1500L     // 等待首帧的最长时间

    /** 用户授权后返回的 MediaProjection 实例（进程内复用） */
    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var resultCode = 0

    @Volatile
    private var resultData: Intent? = null

    /**
     * 授权页正在前台的截止时间戳。
     * 系统「截屏授权」对话框弹出时，若守护服务同时抢占前台，会把授权框顶掉，
     * 因此在这段时间内暂停弹锁屏，让家长能顺利点「允许」。
     */
    @Volatile
    private var consentUiUntil = 0L

    /** 标记授权页已拉起（默认 60 秒内不抢占前台） */
    fun markConsentUiActive(durationMs: Long = 60_000L) {
        consentUiUntil = System.currentTimeMillis() + durationMs
    }

    /** 授权页已结束 */
    fun clearConsentUi() {
        consentUiUntil = 0L
    }

    /** 当前是否处于「授权页前台」期间 */
    fun isConsentUiActive(): Boolean = System.currentTimeMillis() < consentUiUntil

    /** 是否已经拿到授权结果（尚未创建 projection 也算） */
    fun hasConsent(): Boolean = resultData != null

    /** 是否可以直接截图 */
    fun canCapture(): Boolean = projection != null || resultData != null

    /**
     * 由 [CaptureActivity] 在授权回调里写入（主线程调用）。
     * 刻意**不加锁**：只做 volatile 赋值，避免与正在等待主线程的截图流程互相阻塞。
     */
    fun storeResult(code: Int, data: Intent?) {
        if (data == null) return
        val old = projection
        projection = null
        resultCode = code
        resultData = data
        try {
            old?.stop()
        } catch (t: Throwable) {
            // ignore
        }
    }

    /** 清空授权（用户撤销或截图失败需要重新授权时调用） */
    fun clear() {
        val old = projection
        projection = null
        resultData = null
        try {
            old?.stop()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun ensureProjection(c: Context): MediaProjection? {
        projection?.let { return it }
        val data = resultData ?: return null
        val code = resultCode
        return try {
            val mpm = c.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: return null
            val p = mpm.getMediaProjection(code, data) ?: run {
                resultData = null       // 授权已失效，下次重新弹窗
                return null
            }
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // 用户在系统层停止投屏 / 撤销授权
                    projection = null
                    resultData = null
                    Log.i(TAG, "projection stopped")
                }
            }, Handler(Looper.getMainLooper()))
            projection = p
            p
        } catch (t: Throwable) {
            Log.w(TAG, "getMediaProjection failed", t)
            resultData = null
            null
        }
    }

    /**
     * 抓取一帧当前屏幕，返回 JPEG 字节；失败返回 null。
     * 内部每帧都会新建 VirtualDisplay，抓完即释放，不常驻占用资源。
     */
    @Synchronized
    fun capture(c: Context): ByteArray? {
        val p = ensureProjection(c) ?: return null

        val dm = realMetrics(c)
        val rawW = dm.widthPixels.coerceAtLeast(1)
        val rawH = dm.heightPixels.coerceAtLeast(1)
        val scale = if (rawW > MAX_WIDTH) MAX_WIDTH.toFloat() / rawW else 1f
        val w = (rawW * scale).toInt().coerceAtLeast(1)
        val h = (rawH * scale).toInt().coerceAtLeast(1)
        val dpi = if (dm.densityDpi > 0) dm.densityDpi else 160

        var reader: ImageReader? = null
        var vd: VirtualDisplay? = null
        try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            // 部分固件要求 VirtualDisplay 在主线程创建，这里统一回主线程执行
            vd = createVirtualDisplay(p, reader.surface, w, h, dpi) ?: return null

            // 在后台线程轮询首帧，避免阻塞主线程
            var image: Image? = null
            val deadline = SystemClock.uptimeMillis() + FRAME_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                image = reader.acquireLatestImage()
                if (image != null) break
                try {
                    Thread.sleep(50)
                } catch (t: InterruptedException) {
                    break
                }
            }
            val img = image ?: run {
                Log.w(TAG, "no frame in ${FRAME_WAIT_MS}ms")
                return null
            }
            return try {
                imageToJpeg(img, w, h)
            } finally {
                try {
                    img.close()
                } catch (t: Throwable) {
                    // ignore
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed", t)
            return null
        } finally {
            try {
                vd?.release()
            } catch (t: Throwable) {
                // ignore
            }
            try {
                reader?.close()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun createVirtualDisplay(
        p: MediaProjection,
        surface: Surface,
        w: Int,
        h: Int,
        dpi: Int,
    ): VirtualDisplay? {
        val create = {
            p.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME, w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
        }
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                create()
            } else {
                val latch = CountDownLatch(1)
                val box = arrayOfNulls<VirtualDisplay>(1)
                Handler(Looper.getMainLooper()).post {
                    try {
                        box[0] = create()
                    } catch (t: Throwable) {
                        Log.w(TAG, "createVirtualDisplay on main failed", t)
                    } finally {
                        latch.countDown()
                    }
                }
                try {
                    latch.await(3, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    // ignore
                }
                box[0]
            }
        } catch (t: Throwable) {
            Log.w(TAG, "createVirtualDisplay failed", t)
            null
        }
    }

    /** Image → JPEG（处理 rowStride 对齐产生的行填充） */
    private fun imageToJpeg(image: Image, width: Int, height: Int): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        var padded: Bitmap? = null
        var cropped: Bitmap? = null
        return try {
            padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            cropped = if (paddedWidth == width) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, width, height)
            }
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "encode jpeg failed", t)
            null
        } finally {
            if (cropped != null && cropped !== padded) {
                try {
                    cropped.recycle()
                } catch (t: Throwable) {
                    // ignore
                }
            }
            try {
                padded?.recycle()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(c: Context): DisplayMetrics {
        val m = DisplayMetrics()
        try {
            val wm = c.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.defaultDisplay?.getRealMetrics(m)
        } catch (t: Throwable) {
            Log.w(TAG, "realMetrics failed", t)
        }
        if (m.widthPixels <= 0 || m.heightPixels <= 0) {
            m.setTo(c.resources.displayMetrics)
        }
        return m
    }
}
