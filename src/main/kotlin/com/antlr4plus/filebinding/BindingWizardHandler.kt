package com.antlr4plus.filebinding

import com.antlr4plus.filebinding.FileBindingService.Companion.isG4pFile
import com.antlr4plus.previewService.PreviewService
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/** Processes .g4p files in folder batches, skipping batches with valid bindings. */
object BindingWizardHandler : BindUnbindActionBase() {

    internal data class FolderBatch(
        val folder: VirtualFile,
        val files: List<VirtualFile>,
    )

    fun handle(e: AnActionEvent) {
        val project = e.project ?: return
        val bindingService = project.service<FileBindingService>()
        val projectRoot =
            bindingService.getProjectRoot()
                ?: run {
                    showError(project, "Cannot determine project root")
                    return
                }

        ProgressManager.getInstance()
            .run(
                object : Task.Backgroundable(project, "Scanning for .g4p files...", true) {
                    private var folderBatches: List<FolderBatch> = emptyList()

                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        val g4pFiles = collectG4pFilesInTreeOrder(projectRoot)
                        folderBatches = groupByFolder(g4pFiles)
                    }

                    override fun onSuccess() {
                        if (folderBatches.isEmpty()) {
                            Messages.showInfoMessage(
                                project,
                                "No .g4p files found in the project.",
                                "Binding Wizard",
                            )
                            return
                        }

                        val totalFiles = folderBatches.sumOf { it.files.size }

                        ApplicationManager.getApplication().invokeLater {
                            runWizardSequence(project, folderBatches, totalFiles)
                        }
                    }
                },
            )
    }

    /** Groups by parent folder while retaining the first-seen folder order. */
    private fun groupByFolder(files: List<VirtualFile>): List<FolderBatch> {
        val batches = mutableListOf<FolderBatch>()
        val seenFolders = mutableSetOf<String>()

        for (file in files) {
            val folder = file.parent ?: continue
            val folderPath = folder.path

            if (folderPath !in seenFolders) {
                seenFolders.add(folderPath)
                val folderFiles = files.filter { it.parent?.path == folderPath }
                batches.add(FolderBatch(folder, folderFiles))
            }
        }

        return batches
    }

    /** Traverses directories before files, sorting names within each group and skipping directory symlinks. */
    private fun collectG4pFilesInTreeOrder(root: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()

        VfsUtilCore.visitChildrenRecursively(
            root,
            object : VirtualFileVisitor<Unit>(NO_FOLLOW_SYMLINKS) {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory && file.name.startsWith(".")) {
                        return false
                    }
                    if (!file.isDirectory && file.isG4pFile()) {
                        result.add(file)
                    }
                    return true
                }

                override fun getChildrenIterable(file: VirtualFile): Iterable<VirtualFile>? {
                    if (!file.isDirectory) return null
                    return file.children.sortedWith(
                        compareBy<VirtualFile> { !it.isDirectory }.thenBy { it.name },
                    )
                }
            },
        )

        return result
    }

    /** Selects the batch's first file in the Project tree. */
    private fun selectFilesInProjectTree(project: Project, files: List<VirtualFile>) {
        if (files.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            val projectView = ProjectView.getInstance(project)
            projectView.select(null, files.first(), true)
        }
    }

    private fun isFullyBound(batch: FolderBatch, bindingService: FileBindingService): Boolean =
        batch.files.all { file ->
            bindingService.isBound(file) && !bindingService.isBindingBroken(file)
        }

    private fun runWizardSequence(project: Project, batches: List<FolderBatch>, totalFiles: Int) {
        val bindingService = project.service<FileBindingService>()
        val previewService = project.service<PreviewService>()

        // Read xattrs and resolve bindings off the EDT before presenting dialogs.
        ApplicationManager.getApplication().executeOnPooledThread {
            val fullyBound = batches.associateWith { isFullyBound(it, bindingService) }
            val unboundBatches = batches.filter { fullyBound[it] != true }
            val alreadyBoundCount = batches.size - unboundBatches.size
            val alreadyBoundFiles = batches.filter { fullyBound[it] == true }.sumOf { it.files.size }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (unboundBatches.isEmpty()) {
                    Messages.showInfoMessage(
                        project,
                        "All ${totalFiles} .g4p files in ${batches.size} folders are already bound.",
                        "Binding Wizard",
                    )
                    return@invokeLater
                }

                runPreparedWizardSequence(
                    project,
                    bindingService,
                    previewService,
                    unboundBatches,
                    alreadyBoundCount,
                    alreadyBoundFiles,
                )
            }
        }
    }

    private fun runPreparedWizardSequence(
        project: Project,
        bindingService: FileBindingService,
        previewService: PreviewService,
        unboundBatches: List<FolderBatch>,
        alreadyBoundCount: Int,
        alreadyBoundFiles: Int,
    ) {
        val unboundFiles = unboundBatches.sumOf { it.files.size }
        var boundCount = 0
        var skippedCount = 0
        var batchIndex = 0
        var processedFiles = 0
        var lastBoundFile: VirtualFile? = null

        lateinit var processNextBatch: () -> Unit

        fun advance(batchSize: Int) {
            processedFiles += batchSize
            batchIndex++
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) processNextBatch()
            }
        }

        fun bindAndAdvance(batch: FolderBatch, grammar: VirtualFile) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val successfullyBound =
                    batch.files.filter { file ->
                        bindingService.bind(file, grammar)
                    }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    boundCount += successfullyBound.size
                    successfullyBound.lastOrNull()?.let { lastBoundFile = it }
                    advance(batch.files.size)
                }
            }
        }

        processNextBatch = processNextBatch@{
            if (project.isDisposed) return@processNextBatch
            if (batchIndex >= unboundBatches.size) {
                val message = buildString {
                    append("Binding Wizard Complete\n\n")
                    append("Folders processed: ${unboundBatches.size}\n")
                    if (alreadyBoundCount > 0) {
                        append("Folders skipped (already bound): $alreadyBoundCount\n")
                    }
                    append("Files processed: $unboundFiles\n")
                    if (alreadyBoundFiles > 0) {
                        append("Files skipped (already bound): $alreadyBoundFiles\n")
                    }
                    append("Bound: $boundCount\n")
                    append("Skipped: $skippedCount")
                }
                Messages.showInfoMessage(project, message, "Binding Wizard")

                if (boundCount > 0 && lastBoundFile != null) {
                    previewService.launchProcessing(lastBoundFile, forceRefresh = true)
                }
                return@processNextBatch
            }

            val batch = unboundBatches[batchIndex]
            val referenceDir = batch.folder

            selectFilesInProjectTree(project, batch.files)

            // Resolve xattrs and load VFS children off the EDT.
            ApplicationManager.getApplication().executeOnPooledThread {
                val bindingInfo =
                    batch.files.map { file ->
                        Triple(
                            file,
                            bindingService.getBoundGrammar(file),
                            bindingService.isBindingBroken(file),
                        )
                    }
                val commonBinding =
                    bindingInfo
                        .mapNotNull { (_, binding, isBroken) -> if (!isBroken) binding else null }
                        .groupingBy { it }
                        .eachCount()
                        .maxByOrNull { it.value }
                        ?.key
                val items = buildPickerItems(referenceDir, commonBinding)
                val bestGrammarIndex = findBestGrammarIndex(items, commonBinding)

                ApplicationManager.getApplication().invokeLater showDialog@{
                    if (project.isDisposed) return@showDialog
                    val dialog =
                        WizardBatchDialog(
                            project = project,
                            batch = batch,
                            bindingInfo = bindingInfo,
                            items = items,
                            currentBatchIndex = batchIndex,
                            totalBatches = unboundBatches.size,
                            processedFiles = processedFiles,
                            totalFiles = unboundFiles,
                            bestGrammarIndex = bestGrammarIndex,
                        )

                    if (dialog.showAndGet()) {
                        val selectedItem = dialog.selectedItem
                        when {
                            selectedItem is GrammarPickerItem.GrammarFile -> {
                                bindAndAdvance(batch, selectedItem.file)
                                return@showDialog
                            }
                            selectedItem is GrammarPickerItem.BrowseOption -> {
                                val descriptor =
                                    FileChooserDescriptorFactory.createSingleFileDescriptor("g4")
                                        .withTitle("Select Grammar File")
                                        .withDescription(
                                            "Choose a .g4 grammar file to bind to ${batch.files.size} file(s) in ${batch.folder.name}",
                                        )
                                        .withRoots(bindingService.getProjectRoot())

                                FileChooser.chooseFile(descriptor, project, null) { selectedFile ->
                                    if (selectedFile.extension?.equals("g4", ignoreCase = true) == true) {
                                        bindAndAdvance(batch, selectedFile)
                                    } else {
                                        advance(batch.files.size)
                                    }
                                }
                                return@showDialog
                            }
                            dialog.isSkipped -> {
                                skippedCount += batch.files.size
                            }
                        }
                    } else {
                        val remainingFiles = unboundBatches.drop(batchIndex).sumOf { it.files.size }
                        val abort =
                            Messages.showYesNoDialog(
                                project,
                                "Abort the Binding Wizard?\n\nProgress so far: $boundCount bound, $skippedCount skipped, $remainingFiles files remaining.",
                                "Abort Wizard",
                                Messages.getQuestionIcon(),
                            )
                        if (abort == Messages.YES) {
                            return@showDialog
                        }
                    }

                    advance(batch.files.size)
                }
            }
        }

        processNextBatch()
    }

    private fun buildPickerItems(
        referenceDir: VirtualFile?,
        currentBinding: VirtualFile?,
    ): List<GrammarPickerItem> {
        if (referenceDir == null) {
            return listOf(GrammarPickerItem.BrowseOption)
        }

        val items = mutableListOf<GrammarPickerItem>()

        for (level in -2..2) {
            val grammars = findGrammarsAtLevel(referenceDir, level)
            if (grammars.isNotEmpty()) {
                items.add(GrammarPickerItem.LevelHeader(level))
                grammars.forEach { grammar ->
                    val displayName = buildGrammarDisplayName(grammar, grammars, currentBinding)
                    items.add(GrammarPickerItem.GrammarFile(grammar, displayName))
                }
            }
        }

        items.add(GrammarPickerItem.BrowseOption)
        return items
    }

    private fun buildGrammarDisplayName(
        grammar: VirtualFile,
        grammarsAtLevel: List<VirtualFile>,
        currentBinding: VirtualFile?,
    ): String {
        val baseName = buildGrammarDisplayName(grammar, grammarsAtLevel)
        return if (grammar == currentBinding) {
            "✓ $baseName (current)"
        } else {
            baseName
        }
    }

    /** Prefer the common binding, then a name containing "Parser", then the first grammar. */
    private fun findBestGrammarIndex(
        items: List<GrammarPickerItem>,
        commonBinding: VirtualFile?,
    ): Int {
        if (commonBinding != null) {
            val commonIdx = items.indexOfFirst {
                it is GrammarPickerItem.GrammarFile && it.file == commonBinding
            }
            if (commonIdx >= 0) return commonIdx
        }

        val parserIdx = items.indexOfFirst {
            it is GrammarPickerItem.GrammarFile && it.file.nameWithoutExtension.contains("Parser", ignoreCase = true)
        }
        if (parserIdx >= 0) return parserIdx

        return items.indexOfFirst { it is GrammarPickerItem.GrammarFile }
    }
}
