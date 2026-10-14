package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.refactor.DuplicateRuleResult
import com.antlr4plus.dotG4Support.refactor.duplicateRule
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction

/** Give each external reference its own complete, independently recursive copy of the rule. */
class UniquifyRuleRefs : AnAction() {
    override fun update(e: AnActionEvent) {
        MyActionUtils.showOnlyIfSelectionIsRule(e, "Dup to Make %s Refs Unique")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val ruleName = MyActionUtils.getSelectedPsiElement(e)?.text ?: return
        val editor = e.getData(PlatformDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        when (val result = duplicateRule(editor.document.text, ruleName)) {
            is DuplicateRuleResult.Rejected -> HintManager.getInstance().showErrorHint(editor, result.reason)
            is DuplicateRuleResult.Ready ->
                WriteCommandAction.runWriteCommandAction(
                    project,
                    "Uniquify Rule Refs $ruleName",
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
