package com.antlr4plus.settings

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class StateFormattingPropertiesTest {

    @Test
    fun `formattingPreset defaults to null`() {
        val state = State()
        assertNull(state.formattingPreset)
    }

    @Test
    fun `formattingPreset can be set and retrieved`() {
        val state = State()
        state.formattingPreset = "compact"
        assertEquals("compact", state.formattingPreset)

        state.formattingPreset = "expanded"
        assertEquals("expanded", state.formattingPreset)

        state.formattingPreset = "hanging"
        assertEquals("hanging", state.formattingPreset)

        state.formattingPreset = "custom"
        assertEquals("custom", state.formattingPreset)
    }

    @Test
    fun `alignColons defaults to null`() {
        val state = State()
        assertNull(state.alignColons)
    }

    @Test
    fun `alignColons can be set to valid values`() {
        val state = State()

        state.alignColons = "none"
        assertEquals("none", state.alignColons)

        state.alignColons = "trailing"
        assertEquals("trailing", state.alignColons)

        state.alignColons = "hanging"
        assertEquals("hanging", state.alignColons)
    }

    @Test
    fun `alignSemicolons defaults to null`() {
        val state = State()
        assertNull(state.alignSemicolons)
    }

    @Test
    fun `alignSemicolons can be set to valid values`() {
        val state = State()

        state.alignSemicolons = "none"
        assertEquals("none", state.alignSemicolons)

        state.alignSemicolons = "ownLine"
        assertEquals("ownLine", state.alignSemicolons)

        state.alignSemicolons = "hanging"
        assertEquals("hanging", state.alignSemicolons)
    }

    @Test
    fun `alignTrailingComments defaults to true`() {
        val state = State()
        assertTrue(state.alignTrailingComments)
    }

    @Test
    fun `alignTrailingComments can be toggled`() {
        val state = State()

        state.alignTrailingComments = true
        assertTrue(state.alignTrailingComments)

        state.alignTrailingComments = false
        assertFalse(state.alignTrailingComments)
    }

    @Test
    fun `alignLexerCommands defaults to false`() {
        val state = State()
        assertFalse(state.alignLexerCommands)
    }

    @Test
    fun `alignLexerCommands can be toggled`() {
        val state = State()

        state.alignLexerCommands = true
        assertTrue(state.alignLexerCommands)

        state.alignLexerCommands = false
        assertFalse(state.alignLexerCommands)
    }

    @Test
    fun `formattingIndentWidth defaults to 4`() {
        val state = State()
        assertEquals(4, state.formattingIndentWidth)
    }

    @Test
    fun `formattingIndentWidth can be changed`() {
        val state = State()

        state.formattingIndentWidth = 2
        assertEquals(2, state.formattingIndentWidth)

        state.formattingIndentWidth = 8
        assertEquals(8, state.formattingIndentWidth)
    }

    @Test
    fun `formattingUseTab defaults to false`() {
        val state = State()
        assertFalse(state.formattingUseTab)
    }

    @Test
    fun `formattingUseTab can be toggled`() {
        val state = State()

        state.formattingUseTab = true
        assertTrue(state.formattingUseTab)

        state.formattingUseTab = false
        assertFalse(state.formattingUseTab)
    }

    @Test
    fun `formattingColumnLimit defaults to 150`() {
        val state = State()
        assertEquals(150, state.formattingColumnLimit)
    }

    @Test
    fun `formattingColumnLimit can be changed`() {
        val state = State()

        state.formattingColumnLimit = 80
        assertEquals(80, state.formattingColumnLimit)

        state.formattingColumnLimit = 120
        assertEquals(120, state.formattingColumnLimit)
    }

    @Test
    fun `allowShortRulesOnASingleLine defaults to false`() {
        val state = State()
        assertFalse(state.allowShortRulesOnASingleLine)
    }

    @Test
    fun `allowShortRulesOnASingleLine can be toggled`() {
        val state = State()

        state.allowShortRulesOnASingleLine = false
        assertFalse(state.allowShortRulesOnASingleLine)

        state.allowShortRulesOnASingleLine = true
        assertTrue(state.allowShortRulesOnASingleLine)
    }

    @Test
    fun `all formatting properties can be set together`() {
        val state =
            State().apply {
                formattingPreset = "custom"
                alignColons = "trailing"
                alignSemicolons = "ownLine"
                alignTrailingComments = true
                alignLexerCommands = true
                formattingIndentWidth = 2
                formattingUseTab = true
                formattingColumnLimit = 80
                allowShortRulesOnASingleLine = false
            }

        assertEquals("custom", state.formattingPreset)
        assertEquals("trailing", state.alignColons)
        assertEquals("ownLine", state.alignSemicolons)
        assertTrue(state.alignTrailingComments)
        assertTrue(state.alignLexerCommands)
        assertEquals(2, state.formattingIndentWidth)
        assertTrue(state.formattingUseTab)
        assertEquals(80, state.formattingColumnLimit)
        assertFalse(state.allowShortRulesOnASingleLine)
    }

    @Test
    fun `modification count increments when properties change`() {
        val state = State()
        val initialCount = state.modificationCount

        state.formattingPreset = "compact"
        assertTrue(state.modificationCount > initialCount)
    }
}
