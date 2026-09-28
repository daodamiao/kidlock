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
