package com.antlr4plus.actions

import com.antlr4plus.actions.MyActionUtils.getSelectedPsiElement
import com.antlr4plus.dotG4Support.TokenTypes
import com.antlr4plus.dotG4Support.generators.LexerRuleNotice
import com.antlr4plus.dotG4Support.refactor.ExtractRuleSelection
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Pass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.IntroduceTargetChooser
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import org.antlr.intellij.plugin.parser.ANTLRv4Parser

object ExtractRuleHandler {
    fun handle(e: AnActionEvent) {
        val psiFile = e.getData(LangDataKeys.PSI_FILE) ?: return

        val editor = e.getData(PlatformDataKeys.EDITOR) ?: return
        val selectionModel = editor.selectionModel

        if (!selectionModel.hasSelection()) {
            val el = getSelectedPsiElement(e) ?: return
            val expressions = findExtractableRules(el)
            if (expressions.isEmpty()) return

            IntroduceTargetChooser.showChooser(
                editor,
                expressions,
                object : Pass<PsiElement>() {
                    override fun pass(element: PsiElement?) {
                        element?.let {
                            selectionModel.setSelection(it.textOffset, it.textRange.endOffset)
                            extractSelection(psiFile, editor, selectionModel)
                        }
                    }
                },
            ) {
                it?.text ?: ""
            }
        } else {
            extractSelection(psiFile, editor, selectionModel)
        }
    }

    fun findExtractableRules(context: PsiElement): List<PsiElement> {
        val candidateTypes =
            setOf(
                TokenTypes.getRuleElementType(ANTLRv4Parser.RULE_element),
                TokenTypes.getRuleElementType(ANTLRv4Parser.RULE_alternative),
                TokenTypes.getRuleElementType(ANTLRv4Parser.RULE_lexerElement),
                TokenTypes.getRuleElementType(ANTLRv4Parser.RULE_lexerElements),
            )
        val expressions = mutableListOf<PsiElement>()
        var parent: PsiElement? = context
        while (parent != null) {
            if (candidateTypes.contains(parent.node?.elementType)) {
                expressions.add(parent)
            }
            parent = parent.parent
        }
        return expressions
    }

    private fun extractSelection(psiFile: PsiFile, editor: Editor, selectionModel: SelectionModel) {
        val document = editor.document
        val selection =
            ExtractRuleSelection.find(document.text, selectionModel.selectionStart, selectionModel.selectionEnd)
        if (selection == null) {
            HintManager.getInstance()
                .showErrorHint(
                    editor,
                    "Select complete grammar elements or alternatives without rule-local labels, actions, or arguments.",
                )
            return
        }
        selectionModel.setSelection(selection.start, selection.end)
        val chooser =
            ChooseExtractedRuleName(
                psiFile.project,
                selection.suggestedName,
                selection::nameError,
                selection.companionLexerRules,
            )
        if (!chooser.showAndGet()) return
        val name = chooser.ruleName ?: return
        val plan = selection.plan(name) ?: return
        WriteCommandAction.runWriteCommandAction(
            psiFile.project,
            "Extract Rule $name",
            null,
            {
                if (plan.applyTo(document)) {
                    val replacement = selection.replacementRange(name, plan)
                    selectionModel.setSelection(replacement.startOffset, replacement.endOffset)
                } else {
                    HintManager.getInstance().showErrorHint(editor, "The grammar changed or contains read-only text.")
                }
            },
        )
    }

    internal class ChooseExtractedRuleName(
        project: Project,
        private val initialName: String,
        private val nameError: (String) -> String?,
        private val companionRules: List<String>,
    ) : DialogWrapper(project, true) {
        private lateinit var nameField: JBTextField
        var ruleName: String? = null
            private set

        init {
            title = "Extract Rule"
            init()
        }

        override fun doValidate(): ValidationInfo? =
            nameError(nameField.text.trim())?.let { ValidationInfo(it, nameField) }

        override fun doOKAction() {
            if (doValidate() != null) return
            ruleName = nameField.text.trim()
            super.doOKAction()
        }

        override fun createCenterPanel(): JComponent {
            nameField = JBTextField(initialName)
            nameField.preferredSize = Dimension(250, nameField.preferredSize.height)
            nameField.selectAll()
            if (companionRules.isEmpty()) return nameField
            return JPanel(BorderLayout()).apply {
                add(nameField, BorderLayout.NORTH)
                add(LexerRuleNotice().apply { showRules(companionRules) }, BorderLayout.CENTER)
            }
        }

        override fun getPreferredFocusedComponent(): JComponent = nameField
    }
}
