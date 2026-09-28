package com.kidlock.app

/**
 * 按键序列匹配器，支持**多组序列同时生效**（任意一组命中即解锁）。
 *  - 序列 1：电视遥控器方向键（默认 上上下下左左右右）
 *  - 序列 2：手机音量键（默认 音量+ 音量+ 音量- 音量-）
 * 超过 GAP_MS 未按下一个键则重新开始匹配；允许“重叠开头”。
 */
class KeySequenceMatcher {
    private var seqs: List<IntArray> = emptyList()
    private var indices: IntArray = IntArray(0)
    private var lastKeyAt = 0L

    /** 兼容旧用法：设置单组序列 */
    fun setSequence(sequence: IntArray?) {
        setSequences(if (sequence == null) emptyList() else listOf(sequence))
    }

    /** 设置多组序列（空的自动忽略） */
    fun setSequences(sequences: List<IntArray>) {
        seqs = sequences.filter { it.isNotEmpty() }
        indices = IntArray(seqs.size)
        reset()
    }

    fun reset() {
        for (i in indices.indices) indices[i] = 0
        lastKeyAt = 0L
    }

    /** 每收到一个 ACTION_DOWN 调用一次；返回 true 表示某一组序列已完整命中 */
    fun feed(keyCode: Int, eventTime: Long): Boolean {
        if (seqs.isEmpty()) return false
        if (lastKeyAt > 0L && eventTime - lastKeyAt > GAP_MS) {
            for (i in indices.indices) indices[i] = 0
        }
        lastKeyAt = eventTime

        var hit = false
        for (i in seqs.indices) {
            val s = seqs[i]
            var idx = indices[i]
            if (idx >= s.size) idx = 0
            if (keyCode == s[idx]) {
                idx++
                if (idx >= s.size) {
                    idx = 0
                    hit = true
                }
            } else {
                idx = if (keyCode == s[0]) 1 else 0
            }
            indices[i] = idx
        }
        return hit
    }

    /** 当前最大匹配进度（用于可选的可视化反馈） */
    fun progress(): Int = indices.maxOrNull() ?: 0

    companion object {
        private const val GAP_MS = 3000L

        /** 电视遥控器默认序列：上上下下左左右右 */
        val DEFAULT_TV_KEYS = intArrayOf(19, 19, 20, 20, 21, 21, 22, 22)

        /** 手机默认序列：音量+ 音量+ 音量- 音量- */
        val DEFAULT_PHONE_KEYS = intArrayOf(24, 24, 25, 25)

        /** Android KeyCode -> 中文名 */
        fun name(code: Int): String = when (code) {
            19 -> "↑"
            20 -> "↓"
            21 -> "←"
            22 -> "→"
            23 -> "OK"
            66 -> "确定"
            4 -> "返回"
            82 -> "菜单"
            62 -> "空格"
            24 -> "音量+"
            25 -> "音量-"
            in 7..16 -> (code - 7).toString()
            else -> "K$code"
        }
    }
}
