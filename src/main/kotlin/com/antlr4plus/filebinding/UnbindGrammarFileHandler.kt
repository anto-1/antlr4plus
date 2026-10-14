package com.antlr4plus.filebinding

import com.antlr4plus.previewService.PreviewService
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service

object UnbindGrammarFileHandler : BindUnbindActionBase() {
    fun handle(event: AnActionEvent) {
        val context = event.validateMultiG4P() ?: return
        val project = context.project
        val g4pFiles = context.files
        val service = project.service<FileBindingService>()

        val previewService = project.getService(PreviewService::class.java)
        val processingIdAtStart = previewService.currentProcessingId()

        ApplicationManager.getApplication().executeOnPooledThread {
            // Removing xattrs is filesystem I/O and must not run in the action's EDT callback.
            service.unbindAll(g4pFiles)

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                ProjectView.getInstance(project).refresh()

                // The unbind still succeeds, but must not clear a preview selected meanwhile.
                if (!previewService.isCurrentProcessing(processingIdAtStart)) return@invokeLater
                previewService.launchProcessing(null)
            }
        }
    }
}
