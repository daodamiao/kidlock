package com.kidlock.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内嵌 Web 配置服务（纯 ServerSocket 实现，零第三方依赖）：
 *  GET  /                      -> 配置页面
 *  GET  /api/status            -> 当前状态（无需密码）
 *  GET  /api/config?pwd=xxx    -> 读取配置
 *  POST /api/config            -> 保存配置（body 为 JSON，含 pwd 字段）
 *  POST /api/action            -> {pwd, action}：unlock / lock / clearUnlock / restartWeb
 */
class ConfigWebServer(private val appContext: Context) {

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var port = 0
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService = Executors.newFixedThreadPool(4)

    @Synchronized
    fun start(p: Int) {
        if (isRunning && port == p) return
        stop()
        try {
            val ss = ServerSocket(p)
            ss.reuseAddress = true
            serverSocket = ss
            port = p
            isRunning = true
            runningCount.incrementAndGet()
            acceptThread = Thread({ loop(ss) }, "kidlock-web").apply { isDaemon = true; start() }
            Log.i(TAG, "web server started on $p")
        } catch (t: Throwable) {
            isRunning = false
            Log.e(TAG, "bind $p failed", t)
        }
    }

    @Synchronized
    fun stop() {
        // 只有真正运行过才扣减全局计数，避免未启动实例拉低计数导致状态显示错误
        val wasRunning = isRunning
        isRunning = false
        try {
            serverSocket?.close()
        } catch (t: Throwable) {
            // ignore
        }
        serverSocket = null
        try {
            acceptThread?.interrupt()
        } catch (t: Throwable) {
            // ignore
        }
        acceptThread = null
        if (wasRunning && runningCount.get() > 0) {
            runningCount.decrementAndGet()
        }
    }

    private fun loop(ss: ServerSocket) {
        while (isRunning) {
            val s = try {
                ss.accept()
            } catch (t: Throwable) {
                if (isRunning) Log.w(TAG, "accept failed", t)
                null
            } ?: break
            pool.execute {
                try {
                    s.soTimeout = 8000
                    handle(s)
                } catch (t: Throwable) {
                    Log.w(TAG, "handle failed", t)
                } finally {
                    try {
                        s.close()
                    } catch (t: Throwable) {
                        // ignore
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    private fun handle(s: Socket) {
        val input: InputStream = s.getInputStream()
        val out: OutputStream = s.getOutputStream()

        val head = StringBuilder()
        var contentLength = 0
        // 读请求行 + 头
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            head.append(line).append('\n')
            if (line.startsWith("Content-Length", true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val firstLine = head.toString().substringBefore('\n')
        val parts = firstLine.split(' ')
        val method = parts.getOrNull(0) ?: "GET"
        val rawPath = parts.getOrNull(1) ?: "/"
        var body = ByteArray(0)
        if (contentLength > 0) {
            body = readFully(input, contentLength)
        }

        val qIndex = rawPath.indexOf('?')
        val path = if (qIndex >= 0) rawPath.substring(0, qIndex) else rawPath
        val query = if (qIndex >= 0) parseQuery(rawPath.substring(qIndex + 1)) else HashMap()

        when {
            path == "/api/status" -> json(out, statusJson())
            path == "/api/config" && method == "GET" -> handleGetConfig(out, query)
            path == "/api/config" && method == "POST" -> handleSaveConfig(out, body)
            path == "/api/action" && method == "POST" -> handleAction(out, body)
            path == "/favicon.ico" -> respond(out, 204, "text/plain", ByteArray(0))
            path == "/" || path == "/index.html" || path == "/config" -> {
                respond(out, 200, "text/html; charset=utf-8", pageBytes())
            }
            else -> respond(out, 404, "text/plain; charset=utf-8", "404".toByteArray(Charset.forName("UTF-8")))
        }
    }

    /**
     * 读取配置：打开页面即自动调用，无需密码（便于展示与编辑）。
     * 出于安全考虑，返回结果中不包含管理密码明文（置空），
     * 只有保存 / 执行操作时才校验密码。
     */
    private fun handleGetConfig(out: OutputStream, query: Map<String, String>) {
        val cfg = ConfigStore.load(appContext)
        val safe = cfg.toJson()
        safe.put("password", "")
        json(out, JSONObject().put("ok", true).put("config", safe))
    }

    private fun handleSaveConfig(out: OutputStream, body: ByteArray) {
        val text = String(body, Charset.forName("UTF-8"))
        val cur = ConfigStore.load(appContext)
        try {
            val o = JSONObject(text)
            val pwd = o.optString("pwd", "")
            if (!checkPwd(pwd, cur)) {
                json(out, JSONObject().put("ok", false).put("error", "密码错误"))
                return
            }
            val next = LockConfig.fromJson(o)
            // 页面未提交新密码（读取时已隐藏）时保持原密码不变
            if (o.optString("password", "").isEmpty()) {
                next.password = cur.password
            }
            val oldPort = cur.port
            ConfigStore.save(appContext, next)
            if (next.port != oldPort) {
                MonitorService.act(appContext, MonitorService.ACTION_RESTART_WEB)
            } else {
                MonitorService.act(appContext, MonitorService.ACTION_EVAL)
            }
            json(out, JSONObject().put("ok", true).put("config", next.toJson()))
        } catch (t: Throwable) {
            Log.e(TAG, "save config failed", t)
            json(out, JSONObject().put("ok", false).put("error", t.message ?: "解析失败"))
        }
    }

    private fun handleAction(out: OutputStream, body: ByteArray) {
        val text = String(body, Charset.forName("UTF-8"))
        val cur = ConfigStore.load(appContext)
        try {
            val o = JSONObject(text)
            if (!checkPwd(o.optString("pwd", ""), cur)) {
                json(out, JSONObject().put("ok", false).put("error", "密码错误"))
                return
            }
            when (o.optString("action", "")) {
                "unlock" -> MonitorService.act(appContext, MonitorService.ACTION_UNLOCK_ONCE)
                "lock" -> MonitorService.act(appContext, MonitorService.ACTION_LOCK_NOW)
                "clearUnlock" -> MonitorService.act(appContext, MonitorService.ACTION_CLEAR_UNLOCK)
                "restartWeb" -> MonitorService.act(appContext, MonitorService.ACTION_RESTART_WEB)
                "backup" -> MonitorService.act(appContext, MonitorService.ACTION_BACKUP)
                "eval" -> MonitorService.act(appContext, MonitorService.ACTION_EVAL)
                else -> {
                    json(out, JSONObject().put("ok", false).put("error", "未知动作"))
                    return
                }
            }
            json(out, JSONObject().put("ok", true))
        } catch (t: Throwable) {
            json(out, JSONObject().put("ok", false).put("error", t.message ?: "解析失败"))
        }
    }

    private fun checkPwd(pwd: String, cfg: LockConfig): Boolean {
        return pwd.isNotEmpty() && pwd == cfg.password
    }

    private fun statusJson(): JSONObject {
        val m = MonitorService.statusMap(appContext)
        val o = JSONObject()
        for ((k, v) in m) {
            when (v) {
                null -> o.put(k, JSONObject.NULL)
                is List<*> -> {
                    val arr = org.json.JSONArray()
                    for (item in v) arr.put(item)
                    o.put(k, arr)
                }
                else -> o.put(k, v)
            }
        }
        o.put("ok", true)
        return o
    }

    // ------------------------------------------------------------------ 工具

    private fun json(out: OutputStream, o: JSONObject) {
        respond(out, 200, "application/json; charset=utf-8", o.toString().toByteArray(Charset.forName("UTF-8")))
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        try {
            val head = (
                "HTTP/1.1 $code ${statusText(code)}\r\n" +
                    "Content-Type: $type\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charset.forName("ISO-8859-1"))
            out.write(head)
            out.write(body)
            out.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "respond failed", t)
        }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (c == '\n'.code) {
                return sb.toString().trimEnd('\r')
            }
            sb.append(c.toChar())
        }
    }

    private fun readFully(input: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return buf.copyOf(off)
    }

    private fun parseQuery(q: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            if (i < 0) {
                map[decode(pair)] = ""
            } else {
                map[decode(pair.substring(0, i))] = decode(pair.substring(i + 1))
            }
        }
        return map
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (t: Throwable) {
        s
    }

    private fun pageBytes(): ByteArray {
        return try {
            appContext.resources.openRawResource(R.raw.config_page).use { it.readBytes() }
        } catch (t: Throwable) {
            Log.e(TAG, "read page failed", t)
            "<html><body>config page missing</body></html>".toByteArray(Charset.forName("UTF-8"))
        }
    }

    companion object {
        private const val TAG = "KidLock.Web"
        private val runningCount = AtomicInteger(0)

        @JvmStatic
        fun isAnyRunning(): Boolean = runningCount.get() > 0
    }
}
