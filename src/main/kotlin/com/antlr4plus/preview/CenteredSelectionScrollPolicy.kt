package com.antlr4plus.preview

/** Shared vertical-centering policy for selection-following preview panes. */
internal object CenteredSelectionScrollPolicy {
    private const val HIGH_FREQUENCY_THRESHOLD_NANOS = 100_000_000L

    /** Rapid selection changes should jump directly so animations cannot queue behind key repeat. */
    fun isHighFrequency(previousUpdateNanos: Long, currentUpdateNanos: Long): Boolean {
        if (previousUpdateNanos <= 0L || currentUpdateNanos < previousUpdateNanos) return false
        return currentUpdateNanos - previousUpdateNanos < HIGH_FREQUENCY_THRESHOLD_NANOS
    }

    /** Viewport start that places an item at the vertical center, clamped to the content bounds. */
    fun centeredViewportStart(
        itemStart: Int,
        itemSize: Int,
        viewportSize: Int,
        contentSize: Int,
    ): Int {
        val unclamped = itemStart + itemSize / 2 - viewportSize / 2
        return unclamped.coerceIn(0, (contentSize - viewportSize).coerceAtLeast(0))
    }

    /** Lazy-list item offset corresponding to the same centered viewport position. */
    fun centeredLazyListOffset(viewportSize: Int, itemSize: Int): Int =
        -((viewportSize - itemSize).coerceAtLeast(0) / 2)
}
