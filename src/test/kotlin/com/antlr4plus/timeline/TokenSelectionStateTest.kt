package com.antlr4plus.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenSelectionStateTest {
    @Test
    fun `shift click extends downward from a stable anchor`() {
        val selection =
            TokenSelectionState()
                .click(index = 2, extend = false, toggle = false)
                .click(index = 6, extend = true, toggle = false)

        assertEquals((2..6).toSet(), selection.indices)
        assertEquals(2, selection.anchor)
        assertEquals(6, selection.activeIndex)
    }

    @Test
    fun `shift click extends upward from a stable anchor`() {
        val selection =
            TokenSelectionState()
                .click(index = 6, extend = false, toggle = false)
                .click(index = 2, extend = true, toggle = false)

        assertEquals((2..6).toSet(), selection.indices)
        assertEquals(6, selection.anchor)
        assertEquals(2, selection.activeIndex)
    }

    @Test
    fun `reversing direction rebuilds the range around the original anchor`() {
        val selection =
            TokenSelectionState()
                .click(index = 4, extend = false, toggle = false)
                .click(index = 8, extend = true, toggle = false)
                .click(index = 1, extend = true, toggle = false)

        assertEquals((1..4).toSet(), selection.indices)
        assertEquals(4, selection.anchor)
        assertEquals(1, selection.activeIndex)
    }

    @Test
    fun `shift click follows the same filtered axis as the rendered list`() {
        val visibleIndices = listOf(2, 5, 8)
        val selection =
            TokenSelectionState()
                .click(index = 2, extend = false, toggle = false, selectionAxis = visibleIndices)
                .click(index = 8, extend = true, toggle = false, selectionAxis = visibleIndices)

        assertEquals(setOf(2, 5, 8), selection.indices)
        assertEquals(2, selection.anchor)
        assertEquals(8, selection.activeIndex)
    }

    @Test
    fun `clearing selection also clears its anchor`() {
        val cleared =
            TokenSelectionState()
                .click(index = 3, extend = false, toggle = false)
                .click(index = 3, extend = false, toggle = false)

        assertEquals(emptySet<Int>(), cleared.indices)
        assertNull(cleared.anchor)
        assertNull(cleared.activeIndex)
    }

    @Test
    fun `down selects the first visible row when no row is active`() {
        val moved =
            TokenSelectionState()
                .move(
                    visibleIndices = listOf(2, 5, 8),
                    direction = TokenNavigationDirection.NEXT,
                    extend = false,
                )

        assertEquals(setOf(2), moved.indices)
        assertEquals(2, moved.anchor)
        assertEquals(2, moved.activeIndex)
    }

    @Test
    fun `up selects the last visible row when no row is active`() {
        val moved =
            TokenSelectionState()
                .move(
                    visibleIndices = listOf(2, 5, 8),
                    direction = TokenNavigationDirection.PREVIOUS,
                    extend = false,
                )

        assertEquals(setOf(8), moved.indices)
        assertEquals(8, moved.anchor)
        assertEquals(8, moved.activeIndex)
    }

    @Test
    fun `arrow movement follows filtered visible row order`() {
        val moved =
            TokenSelectionState.single(2)
                .move(listOf(2, 5, 8), TokenNavigationDirection.NEXT, extend = false)
                .move(listOf(2, 5, 8), TokenNavigationDirection.NEXT, extend = false)
                .move(listOf(2, 5, 8), TokenNavigationDirection.PREVIOUS, extend = false)

        assertEquals(setOf(5), moved.indices)
        assertEquals(5, moved.anchor)
        assertEquals(5, moved.activeIndex)
    }

    @Test
    fun `shift arrows extend and shrink around a stable anchor`() {
        val extended =
            TokenSelectionState.single(5)
                .move(listOf(2, 5, 8, 13), TokenNavigationDirection.NEXT, extend = true)
                .move(listOf(2, 5, 8, 13), TokenNavigationDirection.NEXT, extend = true)
        val shrunk =
            extended.move(
                listOf(2, 5, 8, 13),
                TokenNavigationDirection.PREVIOUS,
                extend = true,
            )

        assertEquals(setOf(5, 8, 13), extended.indices)
        assertEquals(5, extended.anchor)
        assertEquals(13, extended.activeIndex)
        assertEquals(setOf(5, 8), shrunk.indices)
        assertEquals(5, shrunk.anchor)
        assertEquals(8, shrunk.activeIndex)
    }

    @Test
    fun `shift arrows cross the anchor symmetrically`() {
        val crossed =
            TokenSelectionState.single(8)
                .move(listOf(2, 5, 8, 13), TokenNavigationDirection.PREVIOUS, extend = true)
                .move(listOf(2, 5, 8, 13), TokenNavigationDirection.PREVIOUS, extend = true)
                .move(listOf(2, 5, 8, 13), TokenNavigationDirection.NEXT, extend = true)
                .move(listOf(2, 5, 8, 13), TokenNavigationDirection.NEXT, extend = true)
                .move(listOf(2, 5, 8, 13), TokenNavigationDirection.NEXT, extend = true)

        assertEquals(setOf(8, 13), crossed.indices)
        assertEquals(8, crossed.anchor)
        assertEquals(13, crossed.activeIndex)
    }

    @Test
    fun `movement is clamped at visible list boundaries`() {
        val atTop =
            TokenSelectionState.single(2).move(listOf(2, 5, 8), TokenNavigationDirection.PREVIOUS, extend = false)
        val atBottom =
            TokenSelectionState.single(8).move(listOf(2, 5, 8), TokenNavigationDirection.NEXT, extend = false)

        assertEquals(TokenSelectionState.single(2), atTop)
        assertEquals(TokenSelectionState.single(8), atBottom)
    }

    @Test
    fun `movement is unchanged when no rows are visible`() {
        val selection = TokenSelectionState.single(5)

        assertEquals(
            selection,
            selection.move(emptyList(), TokenNavigationDirection.NEXT, extend = false),
        )
    }

    @Test
    fun `movement resumes beside an active row removed by filtering`() {
        val hiddenActive = TokenSelectionState.single(5)

        val movedUp =
            hiddenActive.move(
                visibleIndices = listOf(2, 8, 13),
                direction = TokenNavigationDirection.PREVIOUS,
                extend = false,
            )
        val movedDown =
            hiddenActive.move(
                visibleIndices = listOf(2, 8, 13),
                direction = TokenNavigationDirection.NEXT,
                extend = false,
            )

        assertEquals(TokenSelectionState.single(2), movedUp)
        assertEquals(TokenSelectionState.single(8), movedDown)
    }
}
