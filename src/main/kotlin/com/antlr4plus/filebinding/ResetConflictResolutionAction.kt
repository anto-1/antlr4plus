package com.antlr4plus.filebinding

import com.antlr4plus.interaction.InteractionHandler
import com.antlr4plus.interaction.UserInteraction
import com.antlr4plus.parsing.CompiledParsingService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager

/** Clears conflict choices shared by each selected profile's folder/grammar pair. */
class ResetConflictResolutionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val interaction = UserInteraction.ProjectTreeAction.ResetConflictResolution(e)
        InteractionHandler.process(interaction)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        if (project == null || files == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ANTLR4 Plus")
        if (toolWindow == null || !toolWindow.isVisible) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val bindingService = project.service<FileBindingService>()

        val hasResolved =
            files
                .filter { it.extension?.equals("g4p", ignoreCase = true) == true }
                .any { g4p ->
                    val folderPath = g4p.parent?.path ?: return@any false
                    val grammarPath = bindingService.getBoundGrammarPath(g4p) ?: return@any false
                    CompiledParsingService.hasResolvedConflictsForBinding(folderPath, grammarPath)
                }

        e.presentation.apply {
            isEnabledAndVisible = hasResolved
            text = "Reset Conflict Resolution"
            description = "Clear conflict resolution for .g4p files bound to the same grammar"
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
