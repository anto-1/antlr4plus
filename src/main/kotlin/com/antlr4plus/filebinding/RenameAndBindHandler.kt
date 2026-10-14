package com.antlr4plus.filebinding

import com.antlr4plus.previewService.PreviewService
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

/** Chooses a grammar before renaming inputs to .g4p and binding them. */
object RenameAndBindHandler {
    private val LOG = Logger.getInstance(RenameAndBindHandler::class.java)

    fun handle(event: AnActionEvent) {
        val project = event.project ?: return
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return

        val filesToRename = files.filter {
            !it.isDirectory &&
                !it.extension.equals("g4", ignoreCase = true) &&
                !it.extension.equals("g4p", ignoreCase = true)
        }

        if (filesToRename.isEmpty()) {
            return
        }

        val referenceFile = filesToRename.first()
        val referenceDir =
            referenceFile.parent
                ?: run {
                    Messages.showErrorDialog(
                        project,
                        "Cannot determine directory for ${referenceFile.name}",
                        "Error",
                    )
                    return
                }

        ProgressManager.getInstance()
            .run(
                object :
                    Task.Backgroundable(
                        project,
                        "Finding nearby ANTLR grammars...",
                        true,
                    ) {
                    private var items: List<GrammarPickerItem> = emptyList()

                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        items = buildPickerItems(referenceDir)
                    }

                    override fun onSuccess() {
                        if (!project.isDisposed) showPicker(event, project, filesToRename, items)
                    }
                },
            )
    }

    private fun buildPickerItems(referenceDir: VirtualFile): List<GrammarPickerItem> {
        val items = mutableListOf<GrammarPickerItem>()
        for (level in -2..2) {
            val grammars = findGrammarsAtLevel(referenceDir, level)
            if (grammars.isNotEmpty()) {
                items.add(GrammarPickerItem.LevelHeader(level))
                grammars.forEach { grammar ->
                    items.add(
                        GrammarPickerItem.GrammarFile(
                            grammar,
                            buildGrammarDisplayName(grammar, grammars),
                        ),
                    )
                }
            }
        }
        items.add(GrammarPickerItem.BrowseOption)
        return items
    }

    private fun showPicker(
        event: AnActionEvent,
        project: Project,
        filesToRename: List<VirtualFile>,
        items: List<GrammarPickerItem>,
    ) {
        if (items.size == 1) {
            // With no nearby matches, open the file browser directly.
            handleBrowse(project, filesToRename)
            return
        }

        val popupTitle =
            if (filesToRename.size > 1) {
                "Rename ${filesToRename.size} files to .g4p & Bind Grammar"
            } else {
                "Rename to .g4p & Bind Grammar"
            }

        showGrammarPicker(
            event = event,
            popupTitle = popupTitle,
            items = items,
            onGrammarChosen = { grammar -> performRenameAndBind(project, filesToRename, grammar) },
            onBrowse = { handleBrowse(project, filesToRename) },
        )
    }

    private fun handleBrowse(project: Project, filesToRename: List<VirtualFile>) {
        val descriptor =
            FileChooserDescriptorFactory.createSingleFileDescriptor("g4")
                .withTitle("Select Grammar File")
                .withDescription("Choose a .g4 grammar file to bind")
                .withRoots(project.service<FileBindingService>().getProjectRoot())

        FileChooser.chooseFile(descriptor, project, null) { selectedFile ->
            if (selectedFile.extension?.equals("g4", ignoreCase = true) == true) {
                performRenameAndBind(project, filesToRename, selectedFile)
            } else {
                Messages.showErrorDialog(project, "Please select a .g4 file", "Invalid Selection")
            }
        }
    }

    private fun performRenameAndBind(
        project: Project,
        filesToRename: List<VirtualFile>,
        grammar: VirtualFile,
    ) {
        val previewService = project.service<PreviewService>()
        val processingIdAtStart = previewService.currentProcessingId()
        ProgressManager.getInstance()
            .run(
                object :
                    Task.Backgroundable(
                        project,
                        "Preparing files for binding...",
                        true,
                    ) {
                    private var existingTargets: Map<VirtualFile, VirtualFile?> = emptyMap()

                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        existingTargets = filesToRename.associateWith { file ->
                            file.parent?.findChild("${file.name}.g4p")
                        }
                    }

                    override fun onSuccess() {
                        if (!project.isDisposed) {
                            performRenameAndBindOnEdt(
                                project,
                                filesToRename,
                                grammar,
                                existingTargets,
                                previewService,
                                processingIdAtStart,
                            )
                        }
                    }
                },
            )
    }

    private fun performRenameAndBindOnEdt(
        project: Project,
        filesToRename: List<VirtualFile>,
        grammar: VirtualFile,
        existingTargets: Map<VirtualFile, VirtualFile?>,
        previewService: PreviewService,
        processingIdAtStart: Long,
    ) {
        val renamedFiles = mutableListOf<VirtualFile>()
        val errors = mutableListOf<String>()

        WriteCommandAction.runWriteCommandAction(
            project,
            "Rename to .g4p and Bind",
            null,
            {
                for (file in filesToRename) {
                    try {
                        val oldName = file.name
                        val newName = "${file.name}.g4p"
                        val existingTarget = existingTargets[file]?.takeIf { it.isValid }
                        if (existingTarget != null) {
                            LOG.info("Skipped ${file.name}: $newName already exists")
                            // Bind the existing target without overwriting it.
                            renamedFiles.add(existingTarget)
                            continue
                        }
                        file.rename(RenameAndBindHandler::class.java, newName)
                        renamedFiles.add(file)
                        LOG.info("Renamed $oldName to $newName")
                    } catch (e: Exception) {
                        LOG.warn("Failed to rename ${file.name}: ${e.message}", e)
                        errors.add(
                            "${file.name}:\n${file.path} ->\n${file.parent?.path}/${file.name}.g4p",
                        )
                    }
                }
            },
        )

        if (errors.isNotEmpty()) {
            Messages.showErrorDialog(
                project,
                "Failed to rename some files:\n${errors.joinToString("\n")}",
                "Rename Error",
            )
        }

        if (renamedFiles.isNotEmpty()) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val bindingService = project.service<FileBindingService>()
                val success = bindingService.setAll(renamedFiles, grammar)
                val failedCount =
                    if (success) 0
                    else {
                        renamedFiles.size - renamedFiles.count { bindingService.isBound(it) }
                    }

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (failedCount > 0) {
                        Messages.showWarningDialog(
                            project,
                            "Failed to bind $failedCount file(s). File system may not support extended attributes.",
                            "Binding Warning",
                        )
                    }

                    if (previewService.isCurrentProcessing(processingIdAtStart)) {
                        previewService.launchProcessing(renamedFiles.first(), forceRefresh = true)
                    }

                    ProjectView.getInstance(project).refresh()

                    LOG.info("Renamed and bound ${renamedFiles.size} file(s) to ${grammar.name}")
                }
            }
        }
    }
}
