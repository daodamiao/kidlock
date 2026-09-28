package com.kidlock.app

/**
 * 遥控器按键序列匹配器（默认 上上下下左左右右）。
 * 超过 GAP_MS 未按下一个键则重新开始匹配；允许“重叠开头”。
 */
class KeySequenceMatcher {
    private var seq: IntArray = IntArray(0)
    private var index = 0
    private var lastKeyAt = 0L

    fun setSequence(sequence: IntArray?) {
        seq = sequence ?: IntArray(0)
        reset()
    }

    fun reset() {
        index = 0
        lastKeyAt = 0L
    }

    /** 每收到一个 ACTION_DOWN 调用一次；返回 true 表示整段序列命中 */
    fun feed(keyCode: Int, eventTime: Long): Boolean {
        if (seq.isEmpty()) return false
        if (lastKeyAt > 0L && eventTime - lastKeyAt > GAP_MS) index = 0
        lastKeyAt = eventTime
        if (keyCode == seq[index]) {
            index++
            if (index >= seq.size) {
                index = 0
                return true
            }
        } else {
            index = if (keyCode == seq[0]) 1 else 0
        }
        return false
    }

    fun progress(): Int = index

    companion object {
        private const val GAP_MS = 3000L

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
