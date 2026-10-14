package com.antlr4plus.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CenteredSelectionScrollPolicyTest {
    @Test
    fun `centers an item when content exists on both sides`() {
        assertEquals(
            365,
            CenteredSelectionScrollPolicy.centeredViewportStart(
                itemStart = 500,
                itemSize = 30,
                viewportSize = 300,
                contentSize = 2_000,
            ),
        )
    }

    @Test
    fun `centering clamps at both content boundaries`() {
        assertEquals(
            0,
            CenteredSelectionScrollPolicy.centeredViewportStart(10, 30, 300, 2_000),
        )
        assertEquals(
            1_700,
            CenteredSelectionScrollPolicy.centeredViewportStart(1_980, 20, 300, 2_000),
        )
    }

    @Test
    fun `lazy list offset applies the same vertical center alignment`() {
        assertEquals(-135, CenteredSelectionScrollPolicy.centeredLazyListOffset(300, 30))
        assertEquals(0, CenteredSelectionScrollPolicy.centeredLazyListOffset(20, 30))
    }

    @Test
    fun `rapid updates use immediate scrolling at the hierarchy threshold`() {
        val previous = 1_000_000_000L

        assertTrue(CenteredSelectionScrollPolicy.isHighFrequency(previous, previous + 99_999_999L))
        assertFalse(
            CenteredSelectionScrollPolicy.isHighFrequency(previous, previous + 100_000_000L),
        )
        assertFalse(CenteredSelectionScrollPolicy.isHighFrequency(0L, previous))
        assertFalse(CenteredSelectionScrollPolicy.isHighFrequency(previous, previous - 1L))
    }
}
