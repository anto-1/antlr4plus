package com.antlr4plus.filebinding

import com.antlr4plus.interaction.InteractionHandler
import com.antlr4plus.interaction.UserInteraction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class UnbindGrammarFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val interaction = UserInteraction.ProjectTreeAction.UnbindGrammarFile(e)
        InteractionHandler.process(interaction)
    }

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val g4pFiles = files?.filter { it.extension?.equals("g4p", ignoreCase = true) == true } ?: emptyList()

        e.presentation.apply {
            when {
                g4pFiles.size > 1 -> {
                    text = "Unbind .g4 Grammar from ${g4pFiles.size} Files"
                    description = "Remove the grammar binding from all selected G4P files"
                }
                else -> {
                    text = "Unbind .g4 Grammar"
                    description = "Remove the binding of a parser file from the selected G4P file"
                }
            }
            isEnabledAndVisible = g4pFiles.isNotEmpty()
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
