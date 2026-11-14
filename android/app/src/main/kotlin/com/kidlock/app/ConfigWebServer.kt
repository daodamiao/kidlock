package com.kidlock.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
 *  GET  /remote                -> 全屏遥控器页面（v1.4.2，独立页面，移动优先）
 *  GET  /api/status            -> 当前状态（无需密码；含 version、accessibility、bridge）
 *  GET  /api/screen            -> 当前画面截图（JPEG，无需密码；未授权时拉起系统授权框）
 *  GET  /api/config            -> 读取配置（无需密码；序列不下发，只给步数）
 *  POST /api/config            -> 保存配置（body 为 JSON，含 pwd 字段）
 *  POST /api/action            -> {pwd, action}：showKeys / unlock / lock /
 *                                 clearUnlock（清除临时解锁 + 解除手动锁定）/
 *                                 restartWeb / backup / eval / openAccessibility（跳系统无障碍设置）/
 *                                 reprobeBridge（重新探测盒子系统遥控服务 8080）
 *  POST /api/auth              -> {pwd} 仅校验管理密码（v1.4.2，遥控器页首次进入用）
 *  POST /api/key               -> {pwd, key} 遥控器按键（首选盒子系统遥控服务桥接，
 *                                 失败再走 root 注入 / 无障碍全局返回 / 应用内投递；
 *                                 成功返回 path，失败返回逐条路径 error）
 *
 *  权限模型：读配置免密，写操作（保存 / 查看序列 / 立即锁定 / 发按键等）一律校验管理密码。
 *  前端不再常驻「当前密码」输入框，改为每次写操作弹出密码验证框后提交。
 *  注意：所有 JSON 响应均为 HTTP 200，成功与否一律看 body 的 ok 字段。
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
            path == "/api/screen" -> handleScreen(out)
            path == "/api/config" && method == "GET" -> handleGetConfig(out, query)
            path == "/api/config" && method == "POST" -> handleSaveConfig(out, body)
            path == "/api/action" && method == "POST" -> handleAction(out, body)
            path == "/api/auth" && method == "POST" -> handleAuth(out, body)
            path == "/api/key" && method == "POST" -> handleKey(out, body)
            path == "/favicon.ico" -> respond(out, 204, "text/plain", ByteArray(0))
            path == "/remote" || path == "/remote.html" -> {
                respond(out, 200, "text/html; charset=utf-8", pageBytes(R.raw.remote_page))
            }
            path == "/" || path == "/index.html" || path == "/config" -> {
                respond(out, 200, "text/html; charset=utf-8", pageBytes())
            }
            else -> respond(out, 404, "text/plain; charset=utf-8", "404".toByteArray(Charset.forName("UTF-8")))
        }
    }

    /**
     * 读取配置：打开页面即自动调用，无需密码（便于展示与编辑）。
     * 出于安全考虑，返回结果中：
     *  · 管理密码置空
     *  · 两组解锁序列一律为空（只返回步数），需点击「查看」并验证密码后才下发
     */
    private fun handleGetConfig(out: OutputStream, query: Map<String, String>) {
        val cfg = ConfigStore.load(appContext)
        val safe = cfg.toJson()
        safe.put("password", "")
        safe.put("unlockKeys", org.json.JSONArray())
        safe.put("unlockKeys2", org.json.JSONArray())
        safe.put("keyCount1", cfg.unlockKeys.size)
        safe.put("keyCount2", cfg.unlockKeys2.size)
        json(out, JSONObject().put("ok", true).put("config", safe))
    }

    /**
     * 当前画面截图。按需求「免密码」，任何与盒子同网段的设备都能查看当前画面。
     *  · 已授权 → 直接返回 image/jpeg
     *  · 未授权 → 拉起电视端系统授权框，并返回 JSON 说明
     */
    private fun handleScreen(out: OutputStream) {
        try {
            if (!ScreenCapture.canCapture()) {
                CaptureActivity.start(appContext)
                json(
                    out,
                    JSONObject().put("ok", false)
                        .put("needConsent", true)
                        .put("error", "尚未获得截屏授权：已在电视上弹出授权窗口，请用遥控器点击「允许」后重试")
                )
                return
            }
            val jpg = ScreenCapture.capture(appContext)
            if (jpg == null || jpg.isEmpty()) {
                json(
                    out,
                    JSONObject().put("ok", false)
                        .put("error", "截图失败：请确认已在电视上允许截屏授权，或稍后重试")
                )
                return
            }
            respond(out, 200, "image/jpeg", jpg)
        } catch (t: Throwable) {
            Log.e(TAG, "screen capture failed", t)
            try {
                json(out, JSONObject().put("ok", false).put("error", t.message ?: "截图异常"))
            } catch (t2: Throwable) {
                // ignore
            }
        }
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
            // 未点击「查看」时页面不会提交序列 → 保持原有序列不变
            if (!o.has("unlockKeys")) {
                next.unlockKeys = ArrayList(cur.unlockKeys)
            }
            if (!o.has("unlockKeys2")) {
                next.unlockKeys2 = ArrayList(cur.unlockKeys2)
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
            val action = o.optString("action", "")
            // 「查看序列」：需要密码，返回两组真实序列
            if (action == "showKeys") {
                val cfg = ConfigStore.load(appContext)
                val k1 = org.json.JSONArray()
                for (k in cfg.unlockKeys) k1.put(k)
                val k2 = org.json.JSONArray()
                for (k in cfg.unlockKeys2) k2.put(k)
                json(
                    out,
                    JSONObject().put("ok", true)
                        .put("unlockKeys", k1)
                        .put("unlockKeys2", k2)
                )
                return
            }
            // 重新探测盒子自带的系统遥控服务（服务后启动 / 换了网络时用户可手动点）
            if (action == "reprobeBridge") {
                val ok = N1KeyBridge.reset()
                json(
                    out,
                    JSONObject().put("ok", true)
                        .put("bridge", ok)
                        .put("detail", N1KeyBridge.status())
                )
                return
            }
            // v1.4.3：无 root 设备上一键打开系统无障碍设置，开启后 BACK 走全局返回动作
            if (action == "openAccessibility") {
                try {
                    val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(i)
                    json(out, JSONObject().put("ok", true))
                } catch (t: Throwable) {
                    Log.w(TAG, "open accessibility settings failed", t)
                    json(out, JSONObject().put("ok", false).put("error", t.message ?: "无法打开无障碍设置"))
                }
                return
            }
            when (action) {
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

    /** v1.4.2：遥控器页首次进入时只校验密码，不下发任何敏感配置。 */
    private fun handleAuth(out: OutputStream, body: ByteArray) {
        val text = String(body, Charset.forName("UTF-8"))
        val cur = ConfigStore.load(appContext)
        try {
            val o = JSONObject(text)
            val ok = checkPwd(o.optString("pwd", ""), cur)
            val r = JSONObject().put("ok", ok)
            if (!ok) r.put("error", "密码错误")
            json(out, r)
        } catch (t: Throwable) {
            json(out, JSONObject().put("ok", false).put("error", t.message ?: "解析失败"))
        }
    }

    /** v1.4.3：Web 遥控器按键 {pwd, key}，密码校验 + 白名单 + 分路径发送。 */
    private fun handleKey(out: OutputStream, body: ByteArray) {
        val text = String(body, Charset.forName("UTF-8"))
        val cur = ConfigStore.load(appContext)
        try {
            val o = JSONObject(text)
            if (!checkPwd(o.optString("pwd", ""), cur)) {
                json(out, JSONObject().put("ok", false).put("error", "密码错误"))
                return
            }
            val key = o.optInt("key", 0)
            if (!KeyInjector.isAllowed(key)) {
                json(out, JSONObject().put("ok", false).put("error", "不支持的按键 $key"))
                return
            }
            val ok = KeyInjector.send(appContext, key)
            val r = JSONObject().put("ok", ok)
            if (ok) {
                // 展示实际生效的路径，方便判断是应用内投递还是系统注入
                r.put("path", KeyInjector.lastPath)
            } else {
                // 不再只回一句笼统的「注入失败」，把每条失败路径的原因带给前端
                val detail = KeyInjector.lastError
                val base = if (detail.isEmpty()) "按键注入失败：盒子固件可能限制应用注入按键"
                else "按键注入失败：$detail"
                // 针对最常见的原因给出可操作引导：先确认盒子系统遥控服务(8080)是否可用
                val a11yOn = KidLockAccessibilityService.isConnected ||
                    KidLockAccessibilityService.isEnabledInSettings(appContext)
                val tip = if (!N1KeyBridge.isAvailable()) {
                    if (key == 4 && !a11yOn) {
                        "。提示：请在遥控器页点「重新检测」确认盒子系统遥控服务(8080)是否可用；盒子无此服务时，请到「设置→无障碍」开启「儿童限时管控」或让盒子取得 root"
                    } else {
                        "。提示：请在遥控器页点「重新检测」确认盒子系统遥控服务(8080)是否可用"
                    }
                } else ""
                r.put("error", base + tip)
            }
            json(out, r)
        } catch (t: Throwable) {
            Log.e(TAG, "key inject failed", t)
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
        // v1.4.3：无障碍是否已开启（返回键能否走系统全局返回）
        o.put(
            "accessibility",
            KidLockAccessibilityService.isConnected ||
                KidLockAccessibilityService.isEnabledInSettings(appContext)
        )
        // 盒子系统遥控服务（8080/v1/keyevent）是否可用，遥控器页据此显示连接状态
        o.put("bridge", N1KeyBridge.isAvailable())
        o.put("bridgeDetail", N1KeyBridge.status())
        // v1.4.2：包名版本由 pubspec.yaml 驱动，供配置页 / 遥控器页展示
        try {
            o.put(
                "version",
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
            )
        } catch (t: Throwable) {
            // 取不到版本时留空（前端不显示），避免硬编码版本号与实际构建不一致
            o.put("version", "")
        }
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

    private fun pageBytes(resId: Int = R.raw.config_page): ByteArray {
        return try {
            appContext.resources.openRawResource(resId).use { it.readBytes() }
        } catch (t: Throwable) {
            Log.e(TAG, "read page failed", t)
            "<html><body>page missing</body></html>".toByteArray(Charset.forName("UTF-8"))
        }
    }

    companion object {
        private const val TAG = "KidLock.Web"
        private val runningCount = AtomicInteger(0)

        @JvmStatic
        fun isAnyRunning(): Boolean = runningCount.get() > 0
    }
}
