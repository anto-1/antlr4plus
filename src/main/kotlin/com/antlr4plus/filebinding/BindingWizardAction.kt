package com.antlr4plus.filebinding

import com.antlr4plus.interaction.InteractionHandler
import com.antlr4plus.interaction.UserInteraction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class BindingWizardAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val interaction = UserInteraction.ProjectTreeAction.BindingWizard(e)
        InteractionHandler.process(interaction)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project

        e.presentation.apply {
            text = "Binding Wizard"
            description = "Sequentially bind all .g4p files in the project to their grammars"

            if (project == null) {
                isEnabledAndVisible = false
                return@apply
            }

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ANTLR4 Plus")
            isEnabledAndVisible = toolWindow?.isVisible == true
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
