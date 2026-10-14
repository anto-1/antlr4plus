package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.generators.LiteralChooser
import com.antlr4plus.dotG4Support.generators.LiteralLexerRules
import com.antlr4plus.dotG4Support.refactor.ExtractRuleSelection
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class LiteralPriorityUiTest : BasePlatformTestCase() {
    fun testLiteralChooserUpdatesItsCompanionNoticeWithTheSelection() {
        val generation = LiteralLexerRules.from(source)!!
        val chooser = LiteralChooser(project, generation.rules.toMutableList(), generation::additionalRules)
        try {
            val root = chooser.tree.model.root as DefaultMutableTreeNode
            for (i in 1 until root.childCount) {
                chooser.tree.selectionPath = TreePath((root.getChildAt(i) as DefaultMutableTreeNode).path)
            }
            val chosen = chooser.selectedElements.filterNotNull()
            assertEquals(listOf(generation.rules.first()), chosen)
            val additional = generation.additionalRules(chosen).single()
            val notice = descendants(rootOf(chooser.tree)).filterIsInstance<JTextArea>().single()
            assertTrue(notice.text.contains(additional))
            assertTrue(generation.plan(chosen, source.length)!!.resultText().contains(additional))
            chooser.tree.selectionPath = TreePath((root.getChildAt(0) as DefaultMutableTreeNode).path)
            assertTrue(chooser.selectedElements.isEmpty())
            assertFalse(notice.text.contains(additional))
        } finally {
            chooser.disposeIfNeeded()
        }
    }

    fun testExtractionDialogShowsTheDefinitionsIncludedInItsPlan() {
        val selection = selection(source)
        val additional = selection.companionLexerRules.single()
        val dialog =
            ExtractRuleHandler.ChooseExtractedRuleName(
                project,
                selection.suggestedName,
                selection::nameError,
                selection.companionLexerRules,
            )
        try {
            val components = descendants(rootOf(dialog.getPreferredFocusedComponent()))
            assertTrue(components.filterIsInstance<JTextArea>().single().text.contains(additional))
            components.filterIsInstance<JBTextField>().single().text = "keyword"
            components.filterIsInstance<JButton>().single { it.text == "OK" }.doClick()
            assertEquals("keyword", dialog.ruleName)
            assertTrue(selection.plan(dialog.ruleName!!)!!.resultText().contains(additional))
        } finally {
            dialog.disposeIfNeeded()
        }
    }

    fun testComposedEditsAndTheReplacementSelectionApplyAtBothInsertionPositions() {
        val lexerFirst =
            "grammar Demo; options {caseInsensitive=true;} /* 😀 */ ID: [a-z]+; start: ('if' 'a' | 'IF' 'b') EOF;"
        for (text in listOf(source, lexerFirst)) {
            val selection = selection(text)
            val plan = selection.plan("keyword")!!
            val document = EditorFactory.getInstance().createDocument(text)
            WriteCommandAction.runWriteCommandAction(project) { assertTrue(plan.applyTo(document)) }
            assertEquals(plan.resultText(), document.text)
            val replacement = selection.replacementRange("keyword", plan)
            assertEquals("keyword", document.getText(replacement))
            assertTrue(document.text.substring(0, replacement.startOffset).endsWith("start: ("))
            if (text == lexerFirst) assertTrue(replacement.startOffset > selection.start)
        }
    }

    fun testGuardingOnlyTheCompanionInsertionPreventsEveryEdit() {
        val text = source + " tail: 'other'; ID: [a-z]+;"
        val selection = selection(text)
        val plan = selection.plan("keyword")!!
        val document = EditorFactory.getInstance().createDocument(text)
        val insertion = text.indexOf("ID:")
        WriteCommandAction.runWriteCommandAction(project) {
            val guard = document.createGuardedBlock(insertion - 1, insertion + 1)
            try {
                assertFalse(plan.applyTo(document))
                assertEquals(text, document.text)
            } finally {
                document.removeGuardedBlock(guard)
            }
        }
    }

    private fun selection(text: String): ExtractRuleSelection {
        val start = text.indexOf("'if'")
        return ExtractRuleSelection.find(text, start, start + 4)!!
    }

    private fun descendants(root: Component): List<Component> =
        listOf(root) + if (root is Container) root.components.flatMap { descendants(it) } else emptyList()

    // The test dialog peer has no native content pane; its actual Swing components remain connected.
    private fun rootOf(component: Component): Component = generateSequence(component) { it.parent }.last()

    companion object {
        private const val source = "grammar Demo; options {caseInsensitive=true;} start: ('if' 'a' | 'IF' 'b') EOF;"
    }
}
