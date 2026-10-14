package com.antlr4plus.editor

import com.antlr4plus.timeline.TokenTimelineBuilder
import com.intellij.openapi.util.Key
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.antlr.v4.tool.LexerGrammar

class UnicodeCaretHighlightTest : BasePlatformTestCase() {
    fun `test caret highlighting selects whole supplementary tokens and following text`() {
        myFixture.configureByText("unicode.txt", "😀x")
        val grammar = LexerGrammar("lexer grammar Unicode; UNIT:.;")
        val timeline = TokenTimelineBuilder.buildTimeline(grammar, myFixture.editor.document.text)
        val key = Key.create<Boolean>("unicode.caret.test")
        for (offset in 0..2) {
            updateCaretTokenHighlight(myFixture.editor, timeline.tokens, offset, true, true, key)
            val highlight = myFixture.editor.markupModel.allHighlighters.single { it.getUserData(key) == true }
            assertEquals(if (offset < 2) 0 to 2 else 2 to 3, highlight.startOffset to highlight.endOffset)
        }
        clearCaretTokenHighlight(myFixture.editor, key)
        assertFalse(myFixture.editor.markupModel.allHighlighters.any { it.getUserData(key) == true })
    }
}
