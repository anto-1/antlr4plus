package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.actionInlay.GeneratedActionManualSyncSupport
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware

class SyncGeneratedActionToGrammarAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val target =
            if (project != null && editor != null) {
                GeneratedActionManualSyncSupport.resolveSyncTarget(project, editor)
            } else {
                null
            }

        e.presentation.isEnabledAndVisible = target != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        GeneratedActionManualSyncSupport.syncCurrentTarget(project, editor)
    }
}
