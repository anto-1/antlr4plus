package com.antlr4plus.filebinding

import com.antlr4plus.interaction.InteractionHandler
import com.antlr4plus.interaction.UserInteraction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class BindGrammarFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val interaction = UserInteraction.ProjectTreeAction.BindGrammarFile(e)
        InteractionHandler.process(interaction)
    }

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val g4pFiles = files?.filter { it.extension?.equals("g4p", ignoreCase = true) == true } ?: emptyList()

        e.presentation.apply {
            when {
                g4pFiles.size > 1 -> {
                    text = "Bind .g4 Grammar to ${g4pFiles.size} Files"
                    description = "Bind a grammar file to all selected G4P files"
                }
                else -> {
                    text = "Bind .g4 Grammar"
                    description = "Open a file chooser in the directory of the selected G4P file"
                }
            }
            isEnabledAndVisible = g4pFiles.isNotEmpty()
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
