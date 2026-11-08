package com.kidlock.app

import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.nio.charset.Charset

/**
 * 盒子自带「系统遥控服务」的桥接层。
 *
 * 参考 https://github.com/Jimmuy/N1_controler （斐讯 N1 / YYF 电视盒子系统的遥控器）：
 * 那个 App 本身**不注入任何按键**，它只是手机端往盒子发一个 HTTP 请求
 *
 *      POST http://<盒子IP>:8080/v1/keyevent
 *      Content-Type: application/json
 *      {"keycode":4,"longclick":false}
 *
 * 真正执行 `input keyevent` 的是盒子系统里那个常驻服务——它跑在 system/root 身份下，
 * 因此方向键、OK、返回、音量、主页、菜单、电源全都能生效，普通应用拿不到的
 * INJECT_EVENTS 权限问题根本不存在。
 *
 * KidLock 就装在这台盒子上，所以不需要经过局域网，直接访问 127.0.0.1:8080 即可；
 * 由本类在 Kotlin 侧转发，而不是让网页 JS 直连，好处是：
 *   · 无浏览器跨域(CORS)限制；
 *   · 仍走 /api/key 的管理密码校验；
 *   · 能读到 HTTP 状态码与响应体，失败原因可以回传前端。
 *
 * 设计要点：
 *   · 用纯 JDK HttpURLConnection，零第三方依赖，与项目现有 Web 服务一致；
 *   · 探测只做 TCP connect（400ms），连得上即认为服务在，不发探测请求、无副作用；
 *   · 端口未开放时缓存 30 秒失败状态，避免每次按键都等一遍超时；
 *   · 服务返回非 2xx 或请求异常时立即作废缓存，下一次按键重新探测并自动降级到
 *     root / 无障碍 / 注入路径，不影响非 YYF 固件的盒子。
 */
object N1KeyBridge {

    private const val TAG = "KidLock.Bridge"
    private const val PORT = 8080
    private const val KEY_PATH = "/v1/keyevent"
    private const val CONNECT_TIMEOUT = 400
    private const val READ_TIMEOUT = 1500
    private const val PROBE_TTL_MS = 30_000L
    private val UTF8 = Charset.forName("UTF-8")
    private val LOCK = Any()

    /** 已确认可用的宿主地址；null = 尚未探测 / 探测失败。 */
    @Volatile
    private var cachedHost: String? = null

    /** 最近一次探测结果，用于 TTL 内快速失败。 */
    @Volatile
    private var cachedOk: Boolean? = null

    @Volatile
    private var probeAt = 0L

    /** 最近一次失败原因，供 KeyInjector 汇总到前端 error。 */
    @Volatile
    var lastError: String = ""
        private set

    /** 是否检测到盒子系统遥控服务（带缓存，TTL 内不会重复等超时）。 */
    fun isAvailable(): Boolean = ensureHost() != null

    /** 供 /api/status 展示的可读状态。 */
    fun status(): String {
        val h = ensureHost()
        return if (h != null) "已连接 $h:$PORT"
        else lastError.ifEmpty { "未检测到" }
    }

    /** 服务后来才启动时，前端可点「重新检测」主动清缓存。 */
    fun reset(): Boolean {
        synchronized(LOCK) {
            cachedHost = null
            cachedOk = null
            probeAt = 0L
            lastError = ""
        }
        return isAvailable()
    }

    /**
     * 通过盒子系统服务发送一次按键。成功返回 true；任何失败都返回 false 并写好
     * lastError，由 KeyInjector 决定降级路径。
     */
    fun send(keyCode: Int): Boolean {
        val host = ensureHost() ?: return false
        var conn: HttpURLConnection? = null
        return try {
            val body = "{\"keycode\":$keyCode,\"longclick\":false}"
                .toByteArray(UTF8)
            // 显式绕过系统代理：部分盒子固件设了全局 HTTP 代理，会把 loopback 请求带走
            conn = URL("http://$host:$PORT$KEY_PATH")
                .openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json, */*")
            conn.setRequestProperty("Connection", "close")
            conn.outputStream.use {
                it.write(body)
                it.flush()
            }
            val code = conn.responseCode
            val text = readBody(conn, code)
            if (code in 200..299 && !looksRejected(text)) {
                lastError = ""
                Log.i(TAG, "key $keyCode -> $host:$PORT HTTP $code ${text.take(80)}")
                true
            } else {
                lastError = if (code in 200..299) {
                    "系统遥控服务返回失败：$text"
                } else {
                    "系统遥控服务返回 HTTP $code" +
                        (if (text.isEmpty()) "" else "：$text")
                }
                Log.w(TAG, lastError)
                invalidate()
                false
            }
        } catch (t: Throwable) {
            lastError = "系统遥控服务请求失败(${t.javaClass.simpleName}: ${t.message ?: ""})"
            Log.w(TAG, lastError)
            invalidate()
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    /**
     * YYF 服务返回形如 `{"success":true,...}`；明确 `success:false`，或 8080 上其实是
     * 别的 Web 服务（回了 HTML）时都判为失败，避免「HTTP 200 但按键根本没执行」的假成功。
     * 响应体为空的 200 视为成功（不少固件只回空 body）。
     */
    private fun looksRejected(body: String): Boolean {
        if (body.isEmpty()) return false
        val lower = body.lowercase()
        val compact = lower.replace(" ", "")
        return compact.contains("\"success\":false") ||
            compact.contains("\"success\":\"false\"") ||
            lower.contains("<!doctype html") || lower.contains("<html")
    }

    private fun readBody(c: HttpURLConnection, code: Int): String = try {
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        if (stream == null) {
            ""
        } else {
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(stream, UTF8)).use { r ->
                var n = 0
                while (n < 160) {
                    val ch = r.read()
                    if (ch < 0) break
                    sb.append(ch.toChar())
                    n++
                }
            }
            sb.toString().trim()
        }
    } catch (t: Throwable) {
        ""
    }

    /** 取可用宿主：命中缓存直接返回；失败在 TTL 内快速返回 null。 */
    private fun ensureHost(): String? = synchronized(LOCK) {
        val cached = cachedHost
        if (cached != null) return@synchronized cached
        val now = SystemClock.uptimeMillis()
        if (cachedOk == false && now - probeAt < PROBE_TTL_MS) return@synchronized null
        val h = probe()
        probeAt = now
        cachedOk = h != null
        if (h != null) {
            cachedHost = h
            lastError = ""
        } else {
            lastError = "未检测到盒子系统遥控服务(端口 $PORT 未开放)"
        }
        h
    }

    /** 服务异常时作废缓存，下一次按键重新探测；仍受 TTL 约束避免抖动。 */
    private fun invalidate() {
        cachedHost = null
        cachedOk = false
        probeAt = SystemClock.uptimeMillis()
    }

    /**
     * 只做 TCP connect，不发任何请求，避免误触发按键或动作。
     * 先试 loopback（绝大多数固件都监听它，一次即可成功），失败才枚举网卡地址。
     */
    private fun probe(): String? {
        if (canConnect("127.0.0.1")) {
            Log.i(TAG, "system remote service found at 127.0.0.1:$PORT")
            return "127.0.0.1"
        }
        for (host in lanCandidates()) {
            if (canConnect(host)) {
                Log.i(TAG, "system remote service found at $host:$PORT")
                return host
            }
        }
        Log.i(TAG, "no system remote service on port $PORT")
        return null
    }

    private fun canConnect(host: String): Boolean {
        val s = Socket()
        return try {
            s.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT)
            true
        } catch (t: Throwable) {
            false
        } finally {
            try {
                s.close()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    /** 部分固件只把服务绑在局域网网卡上，补上本机 IPv4 作为候选；最多 5 个。 */
    private fun lanCandidates(): List<String> {
        val list = ArrayList<String>()
        try {
            val nis = NetworkInterface.getNetworkInterfaces() ?: return list
            while (nis.hasMoreElements() && list.size < 5) {
                val ni = nis.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements() && list.size < 5) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val h = a.hostAddress ?: continue
                        if (!list.contains(h)) list.add(h)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enumerate network interfaces failed", t)
        }
        return list
    }
}
