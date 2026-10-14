package com.antlr4plus.filebinding

import com.antlr4plus.parsing.CompiledParsingService
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service

/** Clears conflict choices shared by each selected profile's folder/grammar pair. */
object ResetConflictResolutionHandler {

    fun handle(event: AnActionEvent) {
        val project = event.project ?: return
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        val selectedG4pFiles = files.filter {
            it.extension?.equals("g4p", ignoreCase = true) == true
        }

        // Resolve binding xattrs off the EDT before clearing the corresponding conflict choices.
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val bindingService = project.service<FileBindingService>()
            val bindingsToReset =
                selectedG4pFiles
                    .mapNotNull { g4p ->
                        val folderPath = g4p.parent?.path ?: return@mapNotNull null
                        val grammarPath = bindingService.getBoundGrammarPath(g4p) ?: return@mapNotNull null
                        folderPath to grammarPath
                    }
                    .distinct()

            bindingsToReset.forEach { (folderPath, grammarPath) ->
                CompiledParsingService.clearConflictResolutionForBinding(folderPath, grammarPath)
            }

            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) ProjectView.getInstance(project).refresh()
            }
        }
    }
}
