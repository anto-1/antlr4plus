package com.antlr4plus.filebinding

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/** Opens a grammar picker, then renames and binds the selected input files. */
class RenameAndBindAction : AnAction() {

    companion object {
        private const val TOOL_WINDOW_ID = "ANTLR4 Plus"
    }

    override fun actionPerformed(e: AnActionEvent) {
        RenameAndBindHandler.handle(e)
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
        val renamableFiles =
            files?.filter {
                !it.isDirectory &&
                    !it.extension.equals("g4", ignoreCase = true) &&
                    !it.extension.equals("g4p", ignoreCase = true)
            } ?: emptyList()

        e.presentation.apply {
            when {
                renamableFiles.size > 1 -> {
                    text = "Rename to .g4p and Bind Grammar..."
                    description = "Rename ${renamableFiles.size} files to .g4p and bind to a grammar"
                }
                renamableFiles.size == 1 -> {
                    text = "Rename to .g4p and Bind Grammar..."
                    description = "Rename file to .g4p and bind to a grammar"
                }
                else -> {
                    text = "Rename to .g4p and Bind Grammar..."
                    description = "Rename files to .g4p and bind to a grammar"
                }
            }
            isEnabledAndVisible = renamableFiles.isNotEmpty()
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
