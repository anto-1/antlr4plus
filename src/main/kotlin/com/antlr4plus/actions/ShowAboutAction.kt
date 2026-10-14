package com.antlr4plus.actions

import com.antlr4plus.about.AboutDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class ShowAboutAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        AboutDialog.show(e.project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.apply {
            text = "About ANTLR4+"
            description = "Show information about the ANTLR4+ plugin"
            isEnabledAndVisible = true
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
