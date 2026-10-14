package com.antlr4plus.filebinding

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

open class BindUnbindActionBase {
    data class MultiFileContextG4P(val files: List<VirtualFile>, val project: Project)

    protected fun AnActionEvent.validateMultiG4P(): MultiFileContextG4P? {
        val project = project ?: return null
        val files =
            getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
                ?.filter { it.extension?.equals("g4p", ignoreCase = true) == true }
                ?.takeIf { it.isNotEmpty() } ?: return null
        return MultiFileContextG4P(files, project)
    }

    protected fun showError(project: Project, message: String) {
        Messages.showErrorDialog(project, message, "Error")
    }
}
