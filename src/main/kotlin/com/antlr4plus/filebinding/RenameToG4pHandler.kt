package com.antlr4plus.filebinding

import com.antlr4plus.previewService.PreviewService
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

object RenameToG4pHandler {
    private val LOG = Logger.getInstance(RenameToG4pHandler::class.java)

    fun handle(event: AnActionEvent) {
        val project = event.project ?: return
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return

        // Keep selected grammars intact and bind inputs to the first selected grammar.
        val filesToRename = files.filter {
            RenameToG4pSelectionPolicy.isRenamable(it.isDirectory, it.extension)
        }
        val grammarFilesToBind = files.filter {
            RenameToG4pSelectionPolicy.isGrammar(it.isDirectory, it.extension)
        }

        if (filesToRename.isEmpty()) {
            return
        }
        val previewService = project.service<PreviewService>()
        val processingIdAtStart = previewService.currentProcessingId()

        val message = buildString {
            append("Rename ${filesToRename.size} file(s) to .g4p")
            if (grammarFilesToBind.isNotEmpty()) {
                append("\n\nWill auto-bind to grammar: ${grammarFilesToBind.first().name}")
            }
            append("?")
        }

        if (filesToRename.size > 1 || grammarFilesToBind.isNotEmpty()) {
            val result =
                Messages.showYesNoDialog(
                    project,
                    message,
                    "Confirm Rename",
                    Messages.getQuestionIcon(),
                )
            if (result != Messages.YES) {
                return
            }
        }

        val renamedFiles = mutableListOf<VirtualFile>()

        WriteCommandAction.runWriteCommandAction(
            project,
            "Rename to .g4p",
            null,
            {
                val errors = mutableListOf<String>()

                for (file in filesToRename) {
                    try {
                        val newName = "${file.name}.g4p"
                        file.rename(RenameToG4pHandler::class.java, newName)
                        renamedFiles.add(file)
                        LOG.info("Renamed ${file.name} to $newName")
                    } catch (e: Exception) {
                        LOG.warn("Failed to rename ${file.name}: ${e.message}", e)
                        errors.add("${file.name}: ${e.message}")
                    }
                }

                if (errors.isNotEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Failed to rename some files:\n${errors.joinToString("\n")}",
                            "Rename Error",
                        )
                    }
                }
            },
        )

        if (renamedFiles.isNotEmpty()) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val bindingService = project.service<FileBindingService>()

                if (grammarFilesToBind.isNotEmpty()) {
                    val grammarToBind = grammarFilesToBind.first()
                    for (g4pFile in renamedFiles) {
                        try {
                            bindingService.bind(g4pFile, grammarToBind)
                            LOG.info("Auto-bound ${g4pFile.name} to grammar ${grammarToBind.name}")
                        } catch (e: Exception) {
                            LOG.warn("Failed to bind ${g4pFile.name}: ${e.message}", e)
                        }
                    }
                }

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (previewService.isCurrentProcessing(processingIdAtStart)) {
                        previewService.launchProcessing(renamedFiles.first(), forceRefresh = true)
                    }

                    ProjectView.getInstance(project).refresh()
                }
            }
        } else {
            ApplicationManager.getApplication().invokeLater {
                ProjectView.getInstance(project).refresh()
            }
        }
    }
}
