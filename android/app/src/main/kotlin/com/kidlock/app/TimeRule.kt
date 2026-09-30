package com.kidlock.app

import java.util.Calendar

/**
 * 时间规则引擎（纯 Kotlin，无依赖）：
 *  - OPEN  模式：时间段内允许观看，时段外锁定
 *  - BLOCK 模式：仅时间段内锁定
 *  - 多段配置、按星期重复、跨零点（start > end）
 */
object TimeRule {
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val MIN_MS = 60L * 1000L

    /** 当前是否处于锁定状态 */
    fun isLocked(cfg: LockConfig, now: Long, unlockUntil: Long, manualLock: Boolean): Boolean {
        if (!cfg.enabled) return false
        if (unlockUntil > now) return false          // 单次解锁优先
        if (manualLock) return true                  // 手动锁定
        val inSeg = inAnySegment(cfg, now)
        return if (cfg.mode == LockConfig.MODE_BLOCK) inSeg else !inSeg
    }

    /** 当前时刻是否落在任一已启用的时间段内（含跨零点） */
    fun inAnySegment(cfg: LockConfig, now: Long): Boolean {
        if (cfg.segments.isEmpty()) return false
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dow = cal.get(Calendar.DAY_OF_WEEK) - 1 // Calendar.SUNDAY = 1 -> 0

        // 1) 今天生效的段
        for (s in cfg.segments) {
            if (s.days[dow] && covers(s, minute)) return true
        }
        // 2) 昨天生效且跨零点的段（周日 22:00 -> 01:00，周一 00:30 仍算命中）
        val prevDow = (dow + 6) % 7
        for (s in cfg.segments) {
            if (s.days[prevDow] && s.start > s.end && minute < s.end) return true
        }
        return false
    }

    private fun covers(s: Segment, minute: Int): Boolean {
        if (s.start == s.end) return true                       // 起止相同视为全天
        if (s.start < s.end) return minute >= s.start && minute < s.end
        return minute >= s.start || minute < s.end              // 跨零点
    }

    /** 未来 8 天内所有翻转点（各段起止），升序 */
    fun candidates(cfg: LockConfig, now: Long): List<Long> {
        val out = ArrayList<Long>()
        if (cfg.segments.isEmpty()) return out
        val day = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = day.timeInMillis
        for (d in 0..8) {
            val dayStart = base + d * DAY_MS
            val c = Calendar.getInstance().apply { timeInMillis = dayStart }
            val dow = c.get(Calendar.DAY_OF_WEEK) - 1
            for (s in cfg.segments) {
                if (!s.days[dow]) continue
                if (s.start == s.end) continue                   // 全天段无翻转点
                val st = dayStart + s.start * MIN_MS
                var en = dayStart + s.end * MIN_MS
                if (s.end < s.start) en += DAY_MS                // 跨零点：结束落在次日
                if (st > now) out.add(st)
                if (en > now) out.add(en)
            }
        }
        out.sort()
        return out
    }

    /** 下一次状态变化时间（含单次解锁到期），无则 Long.MAX_VALUE */
    fun nextTransitionAfter(cfg: LockConfig, now: Long, unlockUntil: Long): Long {
        var best = Long.MAX_VALUE
        // 只有当前处于锁定态时，临时解锁到期才算一次状态切换；
        // 未锁定时它不是真正的状态变化（避免显示误导、无效闹钟）
        if (isLocked(cfg, now, unlockUntil, false) && unlockUntil > now) best = unlockUntil
        for (t in candidates(cfg, now)) {
            if (t > now && t < best) best = t
        }
        return best
    }

    /**
     * 下一次变为 wantLocked 状态的时间。
     * wantLocked = false 时即“本次锁定的自然结束时刻”，用于封顶单次解锁时长。
     */
    fun nextStateChange(cfg: LockConfig, now: Long, wantLocked: Boolean): Long {
        for (t in candidates(cfg, now)) {
            if (t <= now) continue
            if (isLocked(cfg, t, 0L, false) == wantLocked) return t
        }
        return Long.MAX_VALUE
    }

    // ------------------------------------------------------------------ 人类可读描述

    /** 周一到周日的显示顺序（下标对应 days 数组：0=周日 … 6=周六） */
    private val WEEK_ORDER = intArrayOf(1, 2, 3, 4, 5, 6, 0)
    private val DAY_NAMES = arrayOf("日", "一", "二", "三", "四", "五", "六")

    /**
     * 把全部时间段描述成「一眼看懂」的文字，相同星期的段自动合并成一组，组间换行。例：
     *   每天 18:00-20:00
     *   周一到周五 08:00-09:00、18:00-20:00
     *   周六到周日 全天
     *   周一、周三、周五 12:00-13:00
     */
    fun describeSegments(cfg: LockConfig): String {
        if (cfg.segments.isEmpty()) return "未设置（不生效）"
        // 按「星期组合」分组，LinkedHashMap 保持用户录入顺序
        val groups = LinkedHashMap<String, MutableList<Segment>>()
        for (s in cfg.segments) {
            val key = (0..6).joinToString("") { if (s.days[it]) "1" else "0" }
            groups.getOrPut(key) { ArrayList() }.add(s)
        }
        val lines = ArrayList<String>(groups.size)
        for ((_, list) in groups) {
            val times = list.joinToString("、") { timeText(it) }
            lines.add("${daysText(list[0].days)} $times")
        }
        return lines.joinToString("\n")
    }

    /**
     * 星期描述：连续区间用「周X到周Y」压缩，零散项用「、」连接。
     * 例：每天 / 周一到周五 / 周六到周日 / 周一、周三、周五
     */
    fun daysText(days: BooleanArray): String {
        val on = ArrayList<Int>(7)
        for (d in WEEK_ORDER) if (days[d]) on.add(d)
        if (on.isEmpty()) return "未选星期"
        if (on.size == 7) return "每天"
        val parts = ArrayList<String>()
        var i = 0
        while (i < on.size) {
            var j = i
            // 在「周一到周日」顺序中连续则并入同一区间
            while (j + 1 < on.size &&
                WEEK_ORDER.indexOf(on[j + 1]) == WEEK_ORDER.indexOf(on[j]) + 1
            ) {
                j++
            }
            if (j - i + 1 >= 2) {
                parts.add("周${DAY_NAMES[on[i]]}到周${DAY_NAMES[on[j]]}")
            } else {
                parts.add("周${DAY_NAMES[on[i]]}")
            }
            i = j + 1
        }
        return parts.joinToString("、")
    }

    /** 单个时段描述：全天 / 18:00-20:00 / 22:00-次日01:00 */
    fun timeText(s: Segment): String {
        if (s.start == s.end) return "全天"
        return if (s.start < s.end) {
            "${hhmm(s.start)}-${hhmm(s.end)}"
        } else {
            "${hhmm(s.start)}-次日${hhmm(s.end)}"
        }
    }

    /** 分钟数 -> HH:mm */
    fun hhmm(minute: Int): String {
        var m = minute % 1440
        if (m < 0) m += 1440
        val h = m / 60
        val mm = m % 60
        return "${pad(h)}:${pad(mm)}"
    }

    /** 时间戳 -> MM-dd HH:mm:ss */
    fun stamp(ts: Long): String {
        if (ts <= 0L || ts == Long.MAX_VALUE) return "--"
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return "${pad(c.get(Calendar.MONTH) + 1)}-${pad(c.get(Calendar.DAY_OF_MONTH))} " +
            "${pad(c.get(Calendar.HOUR_OF_DAY))}:${pad(c.get(Calendar.MINUTE))}:${pad(c.get(Calendar.SECOND))}"
    }

    /** 毫秒 -> “1小时20分” / “5分03秒” */
    fun dur(ms: Long): String {
        var v = ms
        if (v < 0) v = 0
        val total = v / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${pad(s.toInt())}秒"
            else -> "${s}秒"
        }
    }

    fun clock(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return "${pad(c.get(Calendar.HOUR_OF_DAY))}:${pad(c.get(Calendar.MINUTE))}:${pad(c.get(Calendar.SECOND))}"
    }

    private fun pad(v: Int): String = if (v < 10) "0$v" else v.toString()
}
