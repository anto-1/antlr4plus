package com.antlr4plus.interaction

import com.intellij.openapi.actionSystem.AnActionEvent

sealed class UserInteraction {
    sealed class ProjectTreeAction : UserInteraction() {
        data class BindGrammarFile(val context: AnActionEvent) : ProjectTreeAction()

        data class UnbindGrammarFile(val context: AnActionEvent) : ProjectTreeAction()

        data class RenameToG4p(val context: AnActionEvent) : ProjectTreeAction()

        data class ResetConflictResolution(val context: AnActionEvent) : ProjectTreeAction()

        data class BindingWizard(val context: AnActionEvent) : ProjectTreeAction()
    }

    sealed class GrammarEditorAction : UserInteraction() {
        data class ExtractRule(val context: AnActionEvent) : GrammarEditorAction()
    }
}
