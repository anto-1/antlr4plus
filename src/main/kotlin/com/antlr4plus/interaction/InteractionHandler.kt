package com.antlr4plus.interaction

import com.antlr4plus.actions.ExtractRuleHandler
import com.antlr4plus.filebinding.BindGrammarFileHandler
import com.antlr4plus.filebinding.BindingWizardHandler
import com.antlr4plus.filebinding.RenameToG4pHandler
import com.antlr4plus.filebinding.ResetConflictResolutionHandler
import com.antlr4plus.filebinding.UnbindGrammarFileHandler
import com.antlr4plus.util.rethrowFatalOrCancellation
import com.intellij.openapi.diagnostic.Logger

object InteractionHandler {
    private val log = Logger.getInstance(InteractionHandler::class.java)

    fun process(interaction: UserInteraction) {
        try {
            when (interaction) {
                is UserInteraction.ProjectTreeAction.BindGrammarFile ->
                    BindGrammarFileHandler.handle(interaction.context)
                is UserInteraction.ProjectTreeAction.UnbindGrammarFile ->
                    UnbindGrammarFileHandler.handle(interaction.context)
                is UserInteraction.ProjectTreeAction.RenameToG4p -> RenameToG4pHandler.handle(interaction.context)
                is UserInteraction.ProjectTreeAction.ResetConflictResolution ->
                    ResetConflictResolutionHandler.handle(interaction.context)
                is UserInteraction.ProjectTreeAction.BindingWizard -> BindingWizardHandler.handle(interaction.context)

                is UserInteraction.GrammarEditorAction.ExtractRule -> ExtractRuleHandler.handle(interaction.context)
            }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            log.warn("Unexpected error processing interaction: $interaction", e)
        }
    }
}
