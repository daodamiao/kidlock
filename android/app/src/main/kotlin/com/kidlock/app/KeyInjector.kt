package com.kidlock.app

import android.app.Instrumentation
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Web 遥控器按键发送。
 *
 * 关键结论：问题不在 KeyEvent 怎么构造，而在**执行注入的进程身份**。
 * Android 的 INJECT_EVENTS 是 signature 级权限，普通应用拿不到；adb shell 手动执行
 * `input keyevent 4` 能成功，是因为它跑在 shell UID 下。
 *
 * 参考 https://github.com/Jimmuy/N1_controler 后确认：N1 / YYF 盒子系统的遥控器 App
 * 自己不注入按键，它只是 POST 到盒子的 `8080/v1/keyevent`，由**盒子自带的系统服务**
 * 以系统身份执行。既然 KidLock 就装在这台盒子上，直接调用那个服务才是正解：
 *
 *  · **首选：盒子系统遥控服务桥接**（N1KeyBridge，127.0.0.1:8080/v1/keyevent）。
 *    与手机端 N1 遥控器走同一接口，方向键 / OK / 返回 / 音量全部由系统执行，
 *    彻底绕开 INJECT_EVENTS；服务不存在时自动快速降级，不影响其它固件。
 *  · **无系统服务时以 root 身份执行** `su -c "input keyevent <code>"`，身份与 adb shell 等价。
 *  · **无 root 时的 BACK 兜底**：无障碍服务的 `performGlobalAction(GLOBAL_ACTION_BACK)`，
 *    由系统执行全局返回，不需要 INJECT_EVENTS，但需用户在「设置→无障碍」开启一次。
 *  · 自家界面在前台：直接把 DOWN/UP 送进该 Activity 的 dispatchKeyEvent()，不经过系统。
 *    锁屏页/家长页照常把按键喂给解锁序列，与物理遥控器一致。
 *  · 音量 24/25：`AudioManager.adjustStreamVolume()`，音量是全局的，无需任何注入权限。
 *  · 其余注入路径（InputManager 反射 / Instrumentation）保留为最后降级。
 *    Instrumentation 对 BACK 常「不报错但静默丢弃」，排在最后避免假成功短路。
 *
 * 每条失败路径都记入 lastError（含系统服务返回码 / shell 真实输出），成功路径记入
 * lastPath，由 ConfigWebServer 回传前端，便于直接定位是哪一环没通。
 */
object KeyInjector {

    private const val TAG = "KidLock.Key"
    private val LOCK = Any()
    private val pathErrors = StringBuilder()

    // 19↑ 20↓ 21← 22→ 23 OK 66 ENTER 4 返回 24 音量+ 25 音量-
    private val ALLOWED = setOf(19, 20, 21, 22, 23, 66, 4, 24, 25)
    private val SYSTEM_KEYS = setOf(4)
    private val VOLUME_KEYS = setOf(24, 25)

    /** null = 尚未探测；true/false 缓存，避免每次按键都跑一次 su。 */
    @Volatile
    private var suAvailable: Boolean? = null

    /** 最近一次失败的原因（多条路径用「；」分隔）；成功时为空。 */
    @Volatile
    var lastError: String = ""
        private set

    /** 最近一次成功的路径名，供前端显示与排查。 */
    @Volatile
    var lastPath: String = ""
        private set

    fun isAllowed(keyCode: Int): Boolean = keyCode in ALLOWED

    /** 发送一次完整的按下 + 抬起，返回是否成功。 */
    fun send(context: Context, keyCode: Int): Boolean {
        if (!isAllowed(keyCode)) {
            lastError = "不支持的按键 $keyCode"
            lastPath = ""
            return false
        }
        return synchronized(LOCK) {
            pathErrors.setLength(0)
            lastPath = ""
            val ok = when {
                // 音量是全局设置，与前台窗口无关，不需要也不应该投递给 Activity
                keyCode in VOLUME_KEYS ->
                    adjustVolume(context, keyCode)
                        || viaBoxService(keyCode)
                        || injectByShell(keyCode)
                        || injectByInputManager(keyCode)
                        || injectByInstrumentation(keyCode)

                // 首选：交给盒子自带的系统遥控服务（N1/YYF 的 8080 接口），由系统身份执行
                viaBoxService(keyCode) -> true

                // 自家界面在前台：直接投递，且能让锁屏页接收解锁序列
                dispatchToSelf(keyCode) -> true

                // 第三方界面 + BACK：root 注入 → 无障碍全局返回 → 其余降级
                keyCode in SYSTEM_KEYS ->
                    injectByShell(keyCode)
                        || backByAccessibility()
                        || injectByInputManager(keyCode)
                        || injectByInstrumentation(keyCode)

                // 第三方界面 + 方向键 / OK：root 注入优先，其余保持原顺序
                else ->
                    injectByShell(keyCode)
                        || injectByInstrumentation(keyCode)
                        || injectByInputManager(keyCode)
            }
            lastError = if (ok) "" else pathErrors.toString().trim().trimEnd('；')
            ok
        }
    }

    /**
     * 首选路径：复用盒子自带的系统遥控服务。
     * 与手机端 N1 遥控器 App 发的是同一个 JSON，只是从本机 loopback 走，
     * 由系统服务以 system/root 身份执行，普通应用的注入权限限制不再存在。
     */
    private fun viaBoxService(keyCode: Int): Boolean {
        return if (N1KeyBridge.send(keyCode)) {
            mark("盒子系统遥控服务 :8080/v1/keyevent")
        } else {
            fail(N1KeyBridge.lastError.ifEmpty { "盒子系统遥控服务不可用" }, null)
        }
    }

    /** 无障碍服务的全局返回动作：无 root 时 BACK 最可靠的路径。 */
    private fun backByAccessibility(): Boolean {
        return if (KidLockAccessibilityService.performBack()) {
            mark("无障碍全局返回")
        } else {
            fail("无障碍服务未开启(设置→无障碍→儿童限时管控)", null)
        }
    }

    /**
     * 把按键直接送进自家前台 Activity 的 dispatchKeyEvent()。
     * 锁屏页 / 家长验证页的 dispatchKeyEvent 会吞掉按键并喂给解锁序列，
     * 与物理遥控器行为完全一致，不需要 INJECT_EVENTS 权限。
     */
    private fun dispatchToSelf(keyCode: Int): Boolean {
        val act = KidLockApp.foregroundActivity ?: return false
        return try {
            val t0 = SystemClock.uptimeMillis()
            val down = KeyEvent(t0, t0, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(t0, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
            if (Looper.myLooper() == Looper.getMainLooper()) {
                act.dispatchKeyEvent(down)
                act.dispatchKeyEvent(up)
            } else {
                val latch = CountDownLatch(1)
                act.runOnUiThread {
                    try {
                        act.dispatchKeyEvent(down)
                        act.dispatchKeyEvent(up)
                    } finally {
                        latch.countDown()
                    }
                }
                if (!latch.await(1500L, TimeUnit.MILLISECONDS)) {
                    return fail("应用内投递超时", null)
                }
            }
            Log.i(TAG, "dispatch key $keyCode to ${act.javaClass.simpleName}")
            mark("应用内投递")
        } catch (t: Throwable) {
            Log.w(TAG, "dispatch to self failed for $keyCode", t)
            fail("应用内投递", t)
        }
    }

    /** 音量键：直接调系统音量，无需 INJECT_EVENTS 权限。 */
    private fun adjustVolume(context: Context, keyCode: Int): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return fail("音量调节：AudioManager 不可用", null)
            val dir = if (keyCode == 24) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            Log.i(TAG, "adjustStreamVolume $dir for key $keyCode")
            mark("系统音量服务")
        } catch (t: Throwable) {
            Log.w(TAG, "adjust volume failed", t)
            fail("音量调节", t)
        }
    }

    /** 反射 InputManager.injectInputEvent()（保留降级用，普通应用通常无权限）。 */
    private fun injectByInputManager(keyCode: Int): Boolean {
        return try {
            val cls = Class.forName("android.hardware.input.InputManager")
            val instance = cls.getMethod("getInstance").invoke(null)
            val intType = Int::class.javaPrimitiveType
                ?: return fail("系统级注入(int 类型缺失)", null)
            val inject = cls.getMethod("injectInputEvent", InputEvent::class.java, intType)
            val t0 = SystemClock.uptimeMillis()
            // 与系统 `input keyevent` 用的构造一致（API 13+ 的 10 参版本）：
            // deviceId = VIRTUAL_KEYBOARD(-1)，flags = FROM_SYSTEM，source = 物理键盘
            val down = KeyEvent(
                t0, t0, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                -1, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
            )
            val okDown = inject.invoke(instance, down, 0) as? Boolean ?: false
            try {
                Thread.sleep(60L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val up = KeyEvent(
                t0, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, 0,
                -1, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
            )
            val okUp = inject.invoke(instance, up, 0) as? Boolean ?: false
            Log.i(TAG, "InputManager inject $keyCode down=$okDown up=$okUp")
            if (okDown && okUp) {
                mark("系统级注入")
            } else {
                fail("系统级注入未生效(down=$okDown,up=$okUp)", null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "InputManager inject failed for $keyCode", t)
            fail("系统级注入", t)
        }
    }

    private fun injectByInstrumentation(keyCode: Int): Boolean {
        // sendKeyDownUpSync 不允许在应用主线程调用；Web 服务的线程池天然满足。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            var result = false
            val t = Thread { result = injectNow(keyCode) }
            t.isDaemon = true
            t.start()
            try {
                t.join(2000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return result
        }
        return injectNow(keyCode)
    }

    private fun injectNow(keyCode: Int): Boolean {
        return try {
            Instrumentation().sendKeyDownUpSync(keyCode)
            Log.i(TAG, "injected key $keyCode")
            mark("Instrumentation")
        } catch (t: Throwable) {
            Log.w(TAG, "instrumentation inject failed for $keyCode", t)
            fail("Instrumentation", t)
        }
    }

    // -------------------------------------------------------------- shell / root

    /** shell 执行结果：合并后的 stdout+stderr、退出码、是否超时。 */
    private class ShellResult(val code: Int, val output: String, val timedOut: Boolean)

    private fun hasSu(): Boolean {
        suAvailable?.let { return it }
        val r = exec(arrayOf("su", "-c", "id"), 1800L)
        val ok = r.code == 0 && r.output.contains("uid=0")
        suAvailable = ok
        Log.i(TAG, "root available=$ok, id=${r.output.take(70)}")
        return ok
    }

    /**
     * 以 root 身份执行 `input keyevent`，等价于 adb shell 手动注入；
     * 无 root 时仍尝试应用 UID 直跑（多数会报 INJECT_EVENTS 权限错误，输出会带回前端）。
     */
    private fun injectByShell(keyCode: Int): Boolean {
        val cmd = "input keyevent $keyCode"
        if (hasSu()) {
            val r = exec(arrayOf("su", "-c", cmd), 3000L)
            if (!r.timedOut && r.code == 0 && !looksFailed(r.output)) {
                return mark("root input keyevent")
            }
            fail(shellReason("root input", r), null)
        } else {
            fail("未取得 root(su 不可用)", null)
        }
        val r2 = exec(arrayOf("sh", "-c", cmd), 2500L)
        if (!r2.timedOut && r2.code == 0 && !looksFailed(r2.output)) {
            return mark("input keyevent")
        }
        fail(shellReason("input keyevent", r2), null)
        return false
    }

    private fun looksFailed(output: String): Boolean {
        val s = output.lowercase()
        return s.contains("exception") || s.contains("permission") ||
            s.contains("denied") || s.contains("not allowed") || s.contains("not found")
    }

    private fun shellReason(name: String, r: ShellResult): String {
        val extra = r.output.take(90).replace('；', ',')
        return "$name(退出码 ${r.code}${if (r.timedOut) ",超时" else ""}" +
            "${if (extra.isEmpty()) "" else "：$extra"})"
    }

    /** 合并 stdout/stderr 读取，带超时保护（API 25 没有 Process.waitFor(timeout)）。 */
    private fun exec(cmd: Array<String>, timeoutMs: Long): ShellResult {
        var proc: Process? = null
        return try {
            proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val p = proc
            try {
                p.outputStream.close()
            } catch (t: Throwable) {
                // ignore
            }
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (sb.length < 240) sb.append(line).append(' ')
                        }
                    }
                } catch (t: Throwable) {
                    // ignore
                }
            }
            reader.isDaemon = true
            reader.start()

            val done = CountDownLatch(1)
            val waiter = Thread {
                try {
                    p.waitFor()
                } catch (t: Throwable) {
                    // ignore
                } finally {
                    done.countDown()
                }
            }
            waiter.isDaemon = true
            waiter.start()
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy()
                return ShellResult(-1, sb.toString().trim(), true)
            }
            val code = try {
                p.exitValue()
            } catch (t: Throwable) {
                -1
            }
            reader.join(200L)
            ShellResult(code, sb.toString().trim(), false)
        } catch (t: Throwable) {
            ShellResult(-2, t.javaClass.simpleName + ":" + (t.message ?: ""), false)
        } finally {
            try {
                proc?.destroy()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    /** 记录一条成功路径并返回 true。 */
    private fun mark(path: String): Boolean {
        lastPath = path
        return true
    }

    /** 记录一条失败原因并返回 false，供 || 链式降级使用。 */
    private fun fail(name: String, t: Throwable?): Boolean {
        pathErrors.append(name)
        if (t != null) {
            pathErrors.append("(")
                .append(t.javaClass.simpleName ?: "Throwable")
                .append("：")
                .append((t.message ?: "").take(90))
                .append(")")
        }
        pathErrors.append("；")
        return false
    }
}
