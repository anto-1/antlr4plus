package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.refactor.InlineRuleResult
import com.antlr4plus.dotG4Support.refactor.inlineRule
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction

class InlineRuleAction : AnAction() {
    override fun update(e: AnActionEvent) {
        MyActionUtils.showOnlyIfSelectionIsRule(e, "Inline Rule %s")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val ruleName = MyActionUtils.getSelectedPsiElement(e)?.text ?: return
        val editor = e.getData(PlatformDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        when (val result = inlineRule(editor.document.text, ruleName)) {
            is InlineRuleResult.Rejected -> HintManager.getInstance().showErrorHint(editor, result.reason)
            is InlineRuleResult.Ready ->
                WriteCommandAction.runWriteCommandAction(
                    project,
                    "Inline Rule $ruleName",
                    null,
                    {
                        if (!result.plan.applyTo(editor.document)) {
                            HintManager.getInstance()
                                .showErrorHint(editor, "The grammar changed or contains read-only text.")
                        }
                    },
                )
        }
    }
}
