package com.kidlock.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个时间段。
 * start / end 为自 00:00 起的分钟数（0..1439），start > end 表示跨零点。
 * days 下标 0 = 周日，1 = 周一 ... 6 = 周六。
 */
class Segment(
    var start: Int = 0,
    var end: Int = 0,
    var days: BooleanArray = BooleanArray(7) { true },
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("start", start)
        o.put("end", end)
        val d = JSONArray()
        for (i in 0..6) d.put(if (days[i]) 1 else 0)
        o.put("days", d)
        return o
    }

    fun toMap(): Map<String, Any?> {
        val list = ArrayList<Int>(7)
        for (i in 0..6) list.add(if (days[i]) 1 else 0)
        return mapOf("start" to start, "end" to end, "days" to list)
    }

    companion object {
        fun fromJson(o: JSONObject): Segment {
            val s = Segment()
            s.start = o.optInt("start", 0)
            s.end = o.optInt("end", 0)
            val d = o.optJSONArray("days")
            if (d != null && d.length() == 7) {
                for (i in 0..6) s.days[i] = d.optInt(i, 0) != 0
            }
            return s
        }

        fun fromMap(m: Map<*, *>): Segment {
            val s = Segment()
            s.start = asInt(m["start"], 0)
            s.end = asInt(m["end"], 0)
            val raw = m["days"]
            if (raw is List<*>) {
                for (i in 0..6) {
                    val v = raw.getOrNull(i)
                    s.days[i] = when (v) {
                        is Boolean -> v
                        is Number -> v.toInt() != 0
                        else -> true
                    }
                }
            }
            return s
        }

        internal fun asInt(v: Any?, def: Int): Int = when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: def
            else -> def
        }
    }
}

/**
 * 全局配置。字段与 Web 配置页 / Flutter 端完全一致，JSON 与 Map 双向互通。
 */
class LockConfig {
    /** 总开关 */
    var enabled: Boolean = true
    /** OPEN = 开放时间段（时段内可看，时段外锁定）；BLOCK = 禁用时间段（仅时段内锁定） */
    var mode: String = MODE_OPEN
    var segments: MutableList<Segment> = ArrayList()
    /** 解锁序列 1：电视遥控器（Android KeyCode），默认 上上下下左左右右 */
    var unlockKeys: MutableList<Int> = ArrayList()
    /** 解锁序列 2：手机音量键，默认 音量+ 音量+ 音量- 音量-（任一组命中即可解锁） */
    var unlockKeys2: MutableList<Int> = ArrayList()
    /** 单次解锁时长（分钟） */
    var singleUnlockMinutes: Int = 30
    /** 管理密码（Web 控制台鉴权） */
    var password: String = "123456"
    /** 内嵌 Web 服务端口 */
    var port: Int = 6666
    /** 到点时强制停止前台应用 */
    var forceStop: Boolean = true
    /** 到点时同时调用 DevicePolicyManager.lockNow()（需已激活设备管理器） */
    var lockNow: Boolean = false

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("mode", mode)
        val segs = JSONArray()
        for (s in segments) segs.put(s.toJson())
        o.put("segments", segs)
        val keys = JSONArray()
        for (k in unlockKeys) keys.put(k)
        o.put("unlockKeys", keys)
        val keys2 = JSONArray()
        for (k in unlockKeys2) keys2.put(k)
        o.put("unlockKeys2", keys2)
        o.put("singleUnlockMinutes", singleUnlockMinutes)
        o.put("password", password)
        o.put("port", port)
        o.put("forceStop", forceStop)
        o.put("lockNow", lockNow)
        return o
    }

    fun toMap(): HashMap<String, Any?> {
        val m = HashMap<String, Any?>()
        m["enabled"] = enabled
        m["mode"] = mode
        val segs = ArrayList<Map<String, Any?>>()
        for (s in segments) segs.add(s.toMap())
        m["segments"] = segs
        m["unlockKeys"] = ArrayList(unlockKeys)
        m["unlockKeys2"] = ArrayList(unlockKeys2)
        m["singleUnlockMinutes"] = singleUnlockMinutes
        m["password"] = password
        m["port"] = port
        m["forceStop"] = forceStop
        m["lockNow"] = lockNow
        return m
    }

    /** 修正非法输入，保证规则引擎拿到的永远是合法配置 */
    fun normalize(): LockConfig {
        if (mode != MODE_BLOCK) mode = MODE_OPEN
        if (port < 1024 || port > 65535) port = 6666
        if (singleUnlockMinutes < 1) singleUnlockMinutes = 1
        if (singleUnlockMinutes > 1440) singleUnlockMinutes = 1440
        if (password.isEmpty()) password = "123456"
        for (s in segments) {
            s.start = s.start.coerceIn(0, 1439)
            s.end = s.end.coerceIn(0, 1439)
        }
        if (unlockKeys.isEmpty()) {
            unlockKeys.addAll(DEFAULT_KEYS)
        }
        if (unlockKeys2.isEmpty()) {
            unlockKeys2.addAll(DEFAULT_KEYS2)
        }
        return this
    }

    companion object {
        const val MODE_OPEN = "OPEN"
        const val MODE_BLOCK = "BLOCK"
        /** 序列 1 默认：上上下下左左右右（电视遥控器） */
        val DEFAULT_KEYS = listOf(19, 19, 20, 20, 21, 21, 22, 22)
        /** 序列 2 默认：音量+ 音量+ 音量- 音量-（手机） */
        val DEFAULT_KEYS2 = listOf(24, 24, 25, 25)

        fun defaultConfig(): LockConfig {
            val c = LockConfig()
            c.segments.add(Segment(18 * 60, 20 * 60, BooleanArray(7) { true }))
            c.unlockKeys.addAll(DEFAULT_KEYS)
            c.unlockKeys2.addAll(DEFAULT_KEYS2)
            return c
        }

        fun fromJson(o: JSONObject): LockConfig {
            val c = defaultConfig()
            c.enabled = o.optBoolean("enabled", c.enabled)
            c.mode = if (MODE_BLOCK.equals(o.optString("mode", ""), true)) MODE_BLOCK else MODE_OPEN
            val segs = o.optJSONArray("segments")
            if (segs != null) {
                // 允许清空全部时间段（数组存在即为准，空数组 = 无生效时段）
                c.segments.clear()
                for (i in 0 until segs.length()) {
                    val so = segs.optJSONObject(i) ?: continue
                    c.segments.add(Segment.fromJson(so))
                }
            }
            val keys = o.optJSONArray("unlockKeys")
            if (keys != null) {
                c.unlockKeys.clear()
                for (i in 0 until keys.length()) c.unlockKeys.add(keys.optInt(i, 0))
            }
            val keys2 = o.optJSONArray("unlockKeys2")
            if (keys2 != null) {
                c.unlockKeys2.clear()
                for (i in 0 until keys2.length()) c.unlockKeys2.add(keys2.optInt(i, 0))
            }
            c.singleUnlockMinutes = o.optInt("singleUnlockMinutes", c.singleUnlockMinutes)
            // 密码留空表示“不修改”（Web 页面读取时密码已隐藏，回传为空）
            val pw = o.optString("password", "")
            if (pw.isNotEmpty()) c.password = pw
            c.port = o.optInt("port", c.port)
            c.forceStop = o.optBoolean("forceStop", c.forceStop)
            c.lockNow = o.optBoolean("lockNow", c.lockNow)
            return c.normalize()
        }

        /** 来自 Flutter MethodChannel 的配置（Map 形式） */
        fun fromMap(m: Map<*, *>): LockConfig {
            val c = defaultConfig()
            c.enabled = m["enabled"] as? Boolean ?: true
            val modeRaw = (m["mode"] as? String) ?: MODE_OPEN
            c.mode = if (MODE_BLOCK.equals(modeRaw, true)) MODE_BLOCK else MODE_OPEN
            val segs = m["segments"]
            if (segs is List<*>) {
                c.segments.clear()
                for (item in segs) {
                    if (item is Map<*, *>) c.segments.add(Segment.fromMap(item))
                }
            }
            val keys = m["unlockKeys"]
            if (keys is List<*>) {
                c.unlockKeys.clear()
                for (k in keys) {
                    val v = Segment.asInt(k, 0)
                    if (v > 0) c.unlockKeys.add(v)
                }
            }
            val keys2 = m["unlockKeys2"]
            if (keys2 is List<*>) {
                c.unlockKeys2.clear()
                for (k in keys2) {
                    val v = Segment.asInt(k, 0)
                    if (v > 0) c.unlockKeys2.add(v)
                }
            }
            c.singleUnlockMinutes = Segment.asInt(m["singleUnlockMinutes"], c.singleUnlockMinutes)
            c.password = (m["password"] as? String) ?: c.password
            c.port = Segment.asInt(m["port"], c.port)
            c.forceStop = m["forceStop"] as? Boolean ?: c.forceStop
            c.lockNow = m["lockNow"] as? Boolean ?: c.lockNow
            return c.normalize()
        }
    }
}
