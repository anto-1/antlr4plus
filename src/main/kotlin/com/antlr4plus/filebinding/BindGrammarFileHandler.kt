package com.antlr4plus.filebinding

import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

object BindGrammarFileHandler : BindUnbindActionBase() {

    fun handle(e: AnActionEvent) {
        val context = e.validateMultiG4P() ?: return
        val g4pFiles = context.files
        val project = context.project

        val referenceG4p = g4pFiles.first()
        val referenceDir =
            referenceG4p.parent
                ?: run {
                    showError(project, "Cannot determine directory for ${referenceG4p.name}")
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
                        if (!project.isDisposed) showPicker(e, project, g4pFiles, referenceG4p, items)
                    }
                },
            )
    }

    private fun buildPickerItems(referenceDir: VirtualFile): List<GrammarPickerItem> {
        // Search order: same folder, siblings, two ancestor levels, then two descendant levels.
        val items = mutableListOf<GrammarPickerItem>()

        findGrammarsAtLevel(referenceDir, 0)
            .takeIf { it.isNotEmpty() }
            ?.let { grammars ->
                items.add(GrammarPickerItem.LevelHeader(0))
                grammars.forEach { grammar ->
                    items.add(
                        GrammarPickerItem.GrammarFile(
                            grammar,
                            buildGrammarDisplayName(grammar, grammars),
                        ),
                    )
                }
            }

        findGrammarsInSiblingFolders(referenceDir)
            .takeIf { it.isNotEmpty() }
            ?.let { grammars ->
                items.add(GrammarPickerItem.LevelHeader(level = -1, isSibling = true))
                grammars.forEach { grammar ->
                    // Parent names distinguish sibling matches.
                    val displayName = "${grammar.name} (${grammar.parent?.name ?: ""})"
                    items.add(GrammarPickerItem.GrammarFile(grammar, displayName))
                }
            }

        for (level in listOf(-1, -2, 1, 2)) {
            val grammars = findGrammarsAtLevel(referenceDir, level)
            if (grammars.isNotEmpty()) {
                items.add(GrammarPickerItem.LevelHeader(level))
                grammars.forEach { grammar ->
                    val displayName = buildGrammarDisplayName(grammar, grammars)
                    items.add(GrammarPickerItem.GrammarFile(grammar, displayName))
                }
            }
        }

        items.add(GrammarPickerItem.BrowseOption)
        return items
    }

    private fun showPicker(
        e: AnActionEvent,
        project: Project,
        g4pFiles: List<VirtualFile>,
        referenceG4p: VirtualFile,
        items: List<GrammarPickerItem>,
    ) {
        if (items.size == 1) {
            // With no nearby matches, open the file browser directly.
            handleBrowse(project, g4pFiles)
            return
        }

        val popupTitle =
            if (g4pFiles.size > 1) {
                "Bind Grammar to ${g4pFiles.size} files"
            } else {
                "Bind Grammar to ${referenceG4p.name}"
            }

        showGrammarPicker(
            event = e,
            popupTitle = popupTitle,
            items = items,
            onGrammarChosen = { grammar -> performBinding(project, g4pFiles, grammar) },
            onBrowse = { handleBrowse(project, g4pFiles) },
        )
    }

    private fun handleBrowse(project: Project, g4pFiles: List<VirtualFile>) {
        val descriptor =
            FileChooserDescriptorFactory.createSingleFileDescriptor("g4")
                .withTitle("Select Grammar File")
                .withDescription("Choose a .g4 grammar file to bind")
                .withRoots(project.service<FileBindingService>().getProjectRoot())

        FileChooser.chooseFile(descriptor, project, null) { selectedFile ->
            if (selectedFile.extension?.equals("g4", ignoreCase = true) == true) {
                performBinding(project, g4pFiles, selectedFile)
            } else {
                showError(project, "Please select a .g4 file")
            }
        }
    }

    private fun performBinding(
        project: Project,
        g4pFiles: List<VirtualFile>,
        grammar: VirtualFile,
    ) {
        val bindingService = project.service<FileBindingService>()
        val previewService = project.service<PreviewService>()
        val processingIdAtStart = previewService.currentProcessingId()

        ApplicationManager.getApplication().executeOnPooledThread {
            // xattr persistence and canonical path checks are blocking filesystem operations.
            val success = bindingService.setAll(g4pFiles, grammar)
            val failedCount =
                if (success) 0
                else {
                    g4pFiles.size - g4pFiles.count { bindingService.isBound(it) }
                }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (failedCount > 0) {
                    showError(
                        project,
                        "Failed to bind $failedCount file(s). File system may not support extended attributes.",
                    )
                }

                // Do not let a completed filesystem operation steal a newer preview selection.
                if (!previewService.isCurrentProcessing(processingIdAtStart)) return@invokeLater

                // The same .g4p may now reference a different grammar; force reprocessing.
                previewService.launchProcessing(g4pFiles.first(), forceRefresh = true)
            }
        }
    }
}
