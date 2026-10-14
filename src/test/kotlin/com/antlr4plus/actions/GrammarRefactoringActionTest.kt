package com.antlr4plus.actions

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GrammarRefactoringActionTest : BasePlatformTestCase() {
    fun `test inline action applies its validated plan through the editor context`() {
        myFixture.configureByText("Demo.g4", "grammar Demo; start : item+ EOF; item : 'x' 'y';")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("item"))
        val action = InlineRuleAction()
        action.actionPerformed(TestActionEvent(context(), action))
        assertEquals("grammar Demo; start : ('x' 'y')+ EOF; ", myFixture.editor.document.text)
    }

    fun `test inline presentation re-enables after the caret returns to a rule`() {
        myFixture.configureByText("Demo.g4", "grammar Demo; start : item; item : 'x';")
        val action = InlineRuleAction()
        val event = TestActionEvent(context(), action)
        myFixture.editor.caretModel.moveToOffset(0)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("item"))
        action.update(event)
        assertTrue(event.presentation.isEnabled)
        assertEquals("Inline Rule item", event.presentation.text)
    }

    fun `test extraction disables selections spanning separate rules`() {
        val grammar = "grammar Demo; first : 'a' 'b'; second : 'c';"
        myFixture.configureByText("Demo.g4", grammar)
        val action = ExtractRuleAction()
        val event = TestActionEvent(context(), action)
        myFixture.editor.caretModel.moveToOffset(grammar.indexOf("'b'"))
        myFixture.editor.selectionModel.setSelection(grammar.indexOf("'a'"), grammar.indexOf("'c'") + 3)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
        myFixture.editor.selectionModel.setSelection(grammar.indexOf("'a'"), grammar.indexOf("'b'") + 3)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
    }

    fun `test lexer elements are offered for extraction without an existing selection`() {
        val grammar = "lexer grammar Demo; WORD : [a-z]+;"
        myFixture.configureByText("Demo.g4", grammar)
        val element = myFixture.file.findElementAt(grammar.indexOf("[a-z]"))!!
        assertTrue(ExtractRuleHandler.findExtractableRules(element).isNotEmpty())
    }

    // Editor contexts need not provide the project-view VIRTUAL_FILE_ARRAY key.
    private fun context(): DataContext = DataContext { key ->
        when (key) {
            CommonDataKeys.PROJECT.name -> project
            CommonDataKeys.EDITOR.name -> myFixture.editor
            CommonDataKeys.PSI_FILE.name -> myFixture.file
            CommonDataKeys.VIRTUAL_FILE.name -> myFixture.file.virtualFile
            else -> null
        }
    }
}
