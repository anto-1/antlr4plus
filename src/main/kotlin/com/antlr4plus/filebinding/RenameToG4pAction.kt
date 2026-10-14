package com.antlr4plus.filebinding

import com.antlr4plus.interaction.InteractionHandler
import com.antlr4plus.interaction.UserInteraction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class RenameToG4pAction : AnAction() {

    companion object {
        private const val TOOL_WINDOW_ID = "ANTLR4 Plus"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val interaction = UserInteraction.ProjectTreeAction.RenameToG4p(e)
        InteractionHandler.process(interaction)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null || !toolWindow.isVisible) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        // Keep selected grammars intact for auto-binding.
        val renamableFiles =
            files?.filter {
                RenameToG4pSelectionPolicy.isRenamable(it.isDirectory, it.extension)
            } ?: emptyList()
        val grammarFiles =
            files?.filter {
                RenameToG4pSelectionPolicy.isGrammar(it.isDirectory, it.extension)
            } ?: emptyList()

        e.presentation.apply {
            when {
                renamableFiles.size > 1 -> {
                    val bindInfo = if (grammarFiles.isNotEmpty()) " (auto-bind to ${grammarFiles.first().name})" else ""
                    text = "Rename ${renamableFiles.size} Files to .g4p$bindInfo"
                    description = "Append .g4p extension and auto-bind to grammar"
                }
                renamableFiles.size == 1 -> {
                    val bindInfo = if (grammarFiles.isNotEmpty()) " → ${grammarFiles.first().name}" else ""
                    text = "Rename to .g4p$bindInfo"
                    description = "Append .g4p extension and auto-bind to grammar"
                }
                else -> {
                    text = "Rename to .g4p"
                    description = "Append .g4p extension to selected files"
                }
            }
            isEnabledAndVisible = renamableFiles.isNotEmpty()
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
