package com.antlr4plus.timeline

/**
 * Immutable token selection with a stable range anchor and an independently moving active row.
 *
 * Keeping all three values together prevents a timeline refresh from retaining navigation state that belongs to the
 * previous token list.
 */
internal data class TokenSelectionState(
    val indices: Set<Int> = emptySet(),
    val anchor: Int? = null,
    val activeIndex: Int? = null,
) {
    fun click(
        index: Int,
        extend: Boolean,
        toggle: Boolean,
        selectionAxis: List<Int>? = null,
    ): TokenSelectionState {
        require(index >= 0) { "Token index must be non-negative" }

        if (extend && anchor != null) {
            if (selectionAxis != null) {
                val anchorPosition = selectionAxis.indexOf(anchor)
                val targetPosition = selectionAxis.indexOf(index)
                if (anchorPosition >= 0 && targetPosition >= 0) {
                    val rangeStart = minOf(anchorPosition, targetPosition)
                    val rangeEnd = maxOf(anchorPosition, targetPosition)
                    return copy(
                        indices = selectionAxis.subList(rangeStart, rangeEnd + 1).toSet(),
                        activeIndex = index,
                    )
                }
                return single(index)
            }

            val range = if (index >= anchor) anchor..index else index..anchor
            return copy(indices = range.toSet(), activeIndex = index)
        }

        if (toggle) {
            val updated = if (index in indices) indices - index else indices + index
            return TokenSelectionState(updated, index, index)
        }

        return if (indices.size == 1 && index in indices) {
            TokenSelectionState()
        } else {
            single(index)
        }
    }

    /**
     * Moves the active row along the exact ordered axis rendered by the Lexer list.
     *
     * The explicit active row is distinct from [anchor]: the active row moves on every key press, while the anchor
     * stays fixed for Shift+Up/Down range selection. Keeping both in this value also prevents filtering or a timeline
     * refresh from leaving UI-only navigation state behind.
     */
    fun move(
        visibleIndices: List<Int>,
        direction: TokenNavigationDirection,
        extend: Boolean,
    ): TokenSelectionState {
        if (visibleIndices.isEmpty()) return this

        val currentPosition = activeIndex?.let(visibleIndices::indexOf)?.takeIf { it >= 0 }
        val targetPosition =
            when {
                currentPosition != null -> (currentPosition + direction.step).coerceIn(0, visibleIndices.lastIndex)
                activeIndex != null && direction == TokenNavigationDirection.NEXT ->
                    visibleIndices.indexOfFirst { it > activeIndex }.takeIf { it >= 0 } ?: visibleIndices.lastIndex
                activeIndex != null -> visibleIndices.indexOfLast { it < activeIndex }.takeIf { it >= 0 } ?: 0
                direction == TokenNavigationDirection.NEXT -> 0
                else -> visibleIndices.lastIndex
            }
        val target = visibleIndices[targetPosition]

        if (!extend) return single(target)

        val rangeAnchor =
            anchor?.takeIf { visibleIndices.indexOf(it) >= 0 }
                ?: activeIndex?.takeIf { visibleIndices.indexOf(it) >= 0 }
                ?: target
        val anchorPosition = visibleIndices.indexOf(rangeAnchor)
        val rangeStart = minOf(anchorPosition, targetPosition)
        val rangeEnd = maxOf(anchorPosition, targetPosition)

        return TokenSelectionState(
            indices = visibleIndices.subList(rangeStart, rangeEnd + 1).toSet(),
            anchor = rangeAnchor,
            activeIndex = target,
        )
    }

    companion object {
        fun single(index: Int): TokenSelectionState = TokenSelectionState(setOf(index), index, index)

        fun all(indices: Iterable<Int>): TokenSelectionState {
            val selected = indices.toSet()
            return TokenSelectionState(selected, selected.firstOrNull(), selected.lastOrNull())
        }
    }
}

internal enum class TokenNavigationDirection(val step: Int) {
    PREVIOUS(-1),
    NEXT(1),
}
