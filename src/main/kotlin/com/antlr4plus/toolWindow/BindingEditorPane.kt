package com.antlr4plus.toolWindow

import com.antlr4plus.filebinding.BindingOperations.ParsedBinding
import com.antlr4plus.filebinding.BindingOperations.PlannedAction
import com.antlr4plus.filebinding.BindingOperations.buildExecutionPlan
import com.antlr4plus.filebinding.BindingOperations.executePlannedAction
import com.antlr4plus.filebinding.BindingOperations.parseBindings
import com.antlr4plus.filebinding.ProjectPathPolicy
import com.antlr4plus.filebinding.collectCurrentBindingsWithProgress
import com.antlr4plus.filebinding.generateBindingsContent
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import java.awt.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import kotlinx.coroutines.*

/** Stages project-relative input -> grammar bindings until the user clicks Apply. */
@Suppress("KotlinConstantConditions")
object BindingEditorPane {

    private const val PLACEHOLDER_TEXT =
        """# Binding Editor Staging Area (paths relative to project root)
#
# This is a staging area - bindings here are NOT applied until you click "Apply".
# Edit bindings freely, then apply when ready.
#
# Format:
#   g4p_file_path -> grammar_file_path
#
# Examples:
#   src/test/input.g4p -> src/grammars/Java.g4
#   examples/sql.g4p -> grammars/SQL.g4
#   tests/json.g4p -> grammars/JSON.g4
#
# Click "Load Bindings" to load current bindings from the project.
# Click "Apply" to apply staged bindings to the project files.
# Click "Export" to save bindings for sharing with others.
# Click "Import" to load bindings from an exported file.
"""

    private const val STORAGE_KEY = "com.antlr4plus.bindingEditorContent"

    private val highlighter = BindingEditorHighlighter()

    private val editors = mutableMapOf<Project, EditorEx>()
    private val editorToProject = mutableMapOf<EditorEx, Project>()
    private val cleanupRegistered = mutableSetOf<Project>()
    private val highlightingGenerations = ConcurrentHashMap<EditorEx, AtomicLong>()
    private val highlightingTasks = ConcurrentHashMap<EditorEx, Future<*>>()

    private val applyButtons = mutableMapOf<Project, JButton>()
    private val loadBindingsButtons = mutableMapOf<Project, JButton>()
    private val statusLabels = mutableMapOf<Project, JBLabel>()
    private val processingFlags = mutableMapOf<Project, AtomicBoolean>()

    private fun ensureCleanupRegistered(project: Project, parentDisposable: Disposable) {
        if (project.isDisposed) return
        synchronized(cleanupRegistered) {
            if (cleanupRegistered.add(project)) {
                Disposer.register(parentDisposable) {
                    disposeProject(project)
                }
            }
        }
    }

    private fun disposeProject(project: Project) {
        val editor = editors.remove(project)
        if (editor != null) {
            editorToProject.remove(editor)
            highlightingGenerations.remove(editor)?.incrementAndGet()
            // Invalidate results instead of interrupting pooled tasks during platform teardown.
            highlightingTasks.remove(editor)
        }
        applyButtons.remove(project)
        loadBindingsButtons.remove(project)
        statusLabels.remove(project)
        processingFlags.remove(project)
        synchronized(cleanupRegistered) {
            cleanupRegistered.remove(project)
        }
    }

    fun createPane(project: Project, parentDisposable: Disposable): JPanel {
        ensureCleanupRegistered(project, parentDisposable)

        processingFlags[project] = AtomicBoolean(false)

        val mainPanel = JPanel(BorderLayout())

        val toolbarPanel = createToolbar(project)
        mainPanel.add(toolbarPanel, BorderLayout.NORTH)

        val editorPanel = createEditorPanel(project, parentDisposable)
        mainPanel.add(editorPanel, BorderLayout.CENTER)

        val statusLabel = JBLabel("Staging area: g4p_path -> grammar_path. Click 'Apply' to activate bindings.")
        statusLabel.border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
        statusLabels[project] = statusLabel
        mainPanel.add(statusLabel, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun createToolbar(project: Project): JPanel {
        val toolbar =
            FadingToolbarPanel(fadeZoneWidth = 32).apply {
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            }

        val importButton = JButton("Import", AllIcons.ToolbarDecorator.Import)
        importButton.toolTipText = "Import bindings from a file into the staging area"
        importButton.addActionListener { importBindings(project) }
        toolbar.add(importButton)

        toolbar.add(Box.createHorizontalStrut(5))

        val loadBindingsButton = JButton("Load Bindings", AllIcons.Actions.Download)
        loadBindingsButton.toolTipText = "Load current bindings from the project into the staging area"
        loadBindingsButton.addActionListener { loadCurrentBindingsAsync(project) }
        loadBindingsButtons[project] = loadBindingsButton
        toolbar.add(loadBindingsButton)

        toolbar.add(Box.createHorizontalStrut(5))

        val exportButton = JButton("Export", AllIcons.ToolbarDecorator.Export)
        exportButton.toolTipText = "Export staged bindings to a file"
        exportButton.addActionListener { exportBindings(project) }
        toolbar.add(exportButton)

        toolbar.add(Box.createHorizontalStrut(5))

        val applyButton = JButton("Apply", AllIcons.Actions.Execute)
        applyButton.toolTipText = "Apply staged bindings to the project (makes them active)"
        applyButton.addActionListener { applyBindingsAsync(project) }
        applyButtons[project] = applyButton
        toolbar.add(applyButton)

        toolbar.add(Box.createHorizontalStrut(5))

        val clearButton = JButton("Clear", AllIcons.Actions.GC)
        clearButton.toolTipText = "Clear all bindings from the staging area"
        clearButton.addActionListener { clearEditor(project) }
        toolbar.add(clearButton)

        return toolbar
    }

    private fun createEditorPanel(project: Project, parentDisposable: Disposable): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(0, 5, 5, 5)

        val initialContent = loadPersistedContent(project)
        val document = EditorFactory.getInstance().createDocument(initialContent)

        val editor =
            EditorFactory.getInstance()
                .createEditor(
                    document,
                    project,
                    PlainTextFileType.INSTANCE,
                    false,
                ) as EditorEx

        editor.settings.apply {
            isLineNumbersShown = true
            isWhitespacesShown = false
            isLineMarkerAreaShown = true
            isFoldingOutlineShown = false
            additionalLinesCount = 3
            additionalColumnsCount = 3
            isCaretRowShown = true
            isVirtualSpace = false
            isUseSoftWraps = false
        }

        editor.component.minimumSize = Dimension(400, 300)

        editors[project] = editor
        editorToProject[editor] = project
        highlightingGenerations[editor] = AtomicLong()

        document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (IntentionPreviewUtils.isIntentionPreviewActive()) return
                    scheduleHighlighting(editor)
                    persistContent(project, document.text)
                }
            },
            parentDisposable,
        )

        Disposer.register(parentDisposable) {
            editors.remove(project)
            editorToProject.remove(editor)
            highlightingGenerations.remove(editor)?.incrementAndGet()
            highlightingTasks.remove(editor)
            EditorFactory.getInstance().releaseEditor(editor)
        }

        panel.add(editor.component, BorderLayout.CENTER)

        ApplicationManager.getApplication().invokeLater {
            scheduleHighlighting(editor)
        }

        return panel
    }

    /** Resolves paths off the EDT and rejects results superseded by later document edits. */
    private fun scheduleHighlighting(editor: EditorEx) {
        val project = editorToProject[editor] ?: return
        if (project.isDisposed || editor.isDisposed) return

        val generationCounter = highlightingGenerations[editor] ?: return
        val generation = generationCounter.incrementAndGet()
        val document = editor.document
        val text = document.text
        val modificationStamp = document.modificationStamp
        val projectRootPath = project.guessProjectDir()?.path

        // Invalidate older work; interrupting pooled tasks can surface teardown executor errors.
        highlightingTasks.remove(editor)
        val task =
            ApplicationManager.getApplication().executeOnPooledThread {
                val invalidGrammarPaths = projectRootPath?.let { rootPath ->
                    highlighter.findInvalidGrammarPaths(rootPath, text) {
                        !Thread.currentThread().isInterrupted &&
                            generationCounter.get() == generation &&
                            !project.isDisposed &&
                            !editor.isDisposed
                    }
                }
                if (
                    Thread.currentThread().isInterrupted ||
                        generationCounter.get() != generation ||
                        project.isDisposed ||
                        editor.isDisposed
                ) {
                    return@executeOnPooledThread
                }

                ApplicationManager.getApplication().invokeLater {
                    if (
                        generationCounter.get() != generation ||
                            project.isDisposed ||
                            editor.isDisposed ||
                            document.modificationStamp != modificationStamp
                    ) {
                        return@invokeLater
                    }
                    highlighter.applyHighlighting(editor, text, invalidGrammarPaths)
                }
            }
        highlightingTasks[editor] = task
    }

    internal fun hasPendingHighlightingForTest(editor: EditorEx): Boolean = highlightingTasks[editor]?.isDone == false

    private fun importBindings(project: Project) {
        val descriptor =
            FileChooserDescriptor(
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                )
                .withTitle("Import Bindings")
                .withDescription("Select a file containing bindings")

        FileChooser.chooseFile(descriptor, project, null) { file ->
            ProgressManager.getInstance()
                .run(
                    object : Task.Backgroundable(project, "Importing Bindings", true) {
                        private lateinit var importedContent: String

                        override fun run(indicator: ProgressIndicator) {
                            indicator.isIndeterminate = true
                            importedContent = String(file.contentsToByteArray(), Charsets.UTF_8)
                        }

                        override fun onSuccess() {
                            if (project.isDisposed) return
                            setEditorContent(project, importedContent)
                            Messages.showInfoMessage(
                                project,
                                "Bindings loaded into staging area from:\n${file.path}\n\n" +
                                    "Review the bindings in the editor, then click 'Apply' to execute them.\n\n" +
                                    "The plugin will:\n" +
                                    "  • Rename files to .g4p if needed\n" +
                                    "  • Bind each .g4p to its grammar\n" +
                                    "  • Show a preview before making changes",
                                "Import Complete - Ready for Review",
                            )
                        }

                        override fun onThrowable(error: Throwable) {
                            if (!project.isDisposed) {
                                Messages.showErrorDialog(
                                    project,
                                    "Failed to import bindings: ${error.message}",
                                    "Import Failed",
                                )
                            }
                        }
                    },
                )
        }
    }

    private fun isProcessing(project: Project): Boolean = processingFlags[project]?.get() ?: false

    private fun setProcessing(project: Project, processing: Boolean) {
        processingFlags[project]?.set(processing)

        ApplicationManager.getApplication()
            .invokeLater(
                {
                    applyButtons[project]?.isEnabled = !processing
                    loadBindingsButtons[project]?.isEnabled = !processing

                    if (processing) {
                        applyButtons[project]?.toolTipText = "Processing... Click Cancel in the progress bar to abort."
                        loadBindingsButtons[project]?.toolTipText = "Processing..."
                    } else {
                        applyButtons[project]?.toolTipText = "Apply staged bindings to the project (makes them active)"
                        loadBindingsButtons[project]?.toolTipText =
                            "Load current bindings from the project into the staging area"
                    }
                },
                ModalityState.any(),
            )
    }

    private fun updateStatus(project: Project, message: String) {
        ApplicationManager.getApplication()
            .invokeLater(
                {
                    statusLabels[project]?.text = message
                },
                ModalityState.any(),
            )
    }

    private fun loadCurrentBindingsAsync(project: Project) {
        if (isProcessing(project)) {
            Messages.showInfoMessage(project, "An operation is already in progress.", "Please Wait")
            return
        }

        val projectRoot =
            project.guessProjectDir()
                ?: run {
                    Messages.showErrorDialog(
                        project,
                        "Could not determine project root directory.",
                        "Error",
                    )
                    return
                }

        val existingContent = getEditorContent(project)
        val existingBindings = parseBindings(existingContent)
        val hasExistingBindings = existingBindings.isNotEmpty()

        setProcessing(project, true)
        updateStatus(project, "Scanning project for bindings...")

        ProgressManager.getInstance()
            .run(
                object : Task.Backgroundable(project, "Loading Bindings", true) {
                    private var bindings: List<ParsedBinding> = emptyList()
                    private var cancelled = false

                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = false
                        indicator.text = "Scanning for .g4p files..."

                        bindings = collectCurrentBindingsWithProgress(projectRoot, indicator)
                        cancelled = indicator.isCanceled
                    }

                    override fun onSuccess() {
                        setProcessing(project, false)

                        if (cancelled) {
                            updateStatus(project, "Operation cancelled.")
                            return
                        }

                        if (bindings.isEmpty()) {
                            updateStatus(project, "No bindings found.")
                            Messages.showInfoMessage(
                                project,
                                "No bindings found in the project.\n\n" +
                                    "Bind .g4p files to .g4 grammars using the context menu in the Project view.",
                                "No Bindings Found",
                            )
                            return
                        }

                        updateStatus(project, "Found ${bindings.size} binding(s).")

                        if (hasExistingBindings) {
                            val choice =
                                Messages.showDialog(
                                    project,
                                    "The editor already contains bindings.\n\nHow would you like to load the ${bindings.size} new bindings?",
                                    "Load Bindings",
                                    arrayOf("Merge", "Overwrite", "Cancel"),
                                    0,
                                    Messages.getQuestionIcon(),
                                )

                            when (choice) {
                                0 -> {
                                    val existingPaths = existingBindings.map { it.g4pPath }.toSet()
                                    val newBindings = bindings.filter {
                                        it.g4pPath !in existingPaths
                                    }
                                    val allBindings = existingBindings + newBindings
                                    val content = generateBindingsContent(allBindings)
                                    setEditorContent(project, content)
                                    updateStatus(
                                        project,
                                        "Merged ${newBindings.size} new binding(s).",
                                    )
                                }
                                1 -> {
                                    val content = generateBindingsContent(bindings)
                                    setEditorContent(project, content)
                                    updateStatus(project, "Loaded ${bindings.size} binding(s).")
                                }
                                else -> {
                                    updateStatus(project, "Cancelled.")
                                }
                            }
                        } else {
                            val content = generateBindingsContent(bindings)
                            setEditorContent(project, content)
                            updateStatus(project, "Loaded ${bindings.size} binding(s).")
                        }
                    }

                    override fun onCancel() {
                        setProcessing(project, false)
                        updateStatus(project, "Operation cancelled.")
                    }

                    override fun onThrowable(error: Throwable) {
                        setProcessing(project, false)
                        updateStatus(project, "Error: ${error.message}")
                    }
                },
            )
    }

    private fun exportBindings(project: Project) {
        val content = getEditorContent(project)
        if (content.isBlank() || content == PLACEHOLDER_TEXT) {
            Messages.showWarningDialog(
                project,
                "No bindings to export. The editor is empty.",
                "Nothing to Export",
            )
            return
        }

        val descriptor =
            FileChooserDescriptor(
                    false,
                    true,
                    false,
                    false,
                    false,
                    false,
                )
                .withTitle("Export Bindings")
                .withDescription("Select a directory to save the bindings file")

        FileChooser.chooseFile(descriptor, project, null) { directory ->
            val timestamp =
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val projectName = project.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val outputFile = File(directory.path, "antlr4plus_${projectName}_bindings_$timestamp.txt")

            ProgressManager.getInstance()
                .run(
                    object : Task.Backgroundable(project, "Exporting Bindings", true) {
                        override fun run(indicator: ProgressIndicator) {
                            indicator.isIndeterminate = true
                            outputFile.writeText(content, Charsets.UTF_8)
                        }

                        override fun onSuccess() {
                            if (!project.isDisposed) {
                                Messages.showInfoMessage(
                                    project,
                                    "Bindings exported successfully to:\n${outputFile.absolutePath}",
                                    "Export Successful",
                                )
                            }
                        }

                        override fun onThrowable(error: Throwable) {
                            if (!project.isDisposed) {
                                Messages.showErrorDialog(
                                    project,
                                    "Failed to export bindings: ${error.message}",
                                    "Export Failed",
                                )
                            }
                        }
                    },
                )
        }
    }

    /** Plans and applies filesystem changes in background tasks, then refreshes VFS and decorations. */
    private fun applyBindingsAsync(project: Project) {
        if (isProcessing(project)) return

        val content = getEditorContent(project)
        val bindings = parseBindings(content)

        if (bindings.isEmpty()) {
            updateStatus(project, "No valid bindings found.")
            return
        }

        val projectRoot =
            project.guessProjectDir()
                ?: run {
                    updateStatus(project, "Could not determine project root.")
                    return
                }

        setProcessing(project, true)
        updateStatus(project, "Applying ${bindings.size} binding(s)...")

        ProgressManager.getInstance()
            .run(
                object : Task.Backgroundable(project, "Applying Bindings", true) {
                    private var successCount = 0
                    private var failCount = 0
                    private var skippedCount = 0
                    private val successfulG4pPaths = java.util.concurrent.ConcurrentLinkedQueue<java.nio.file.Path>()

                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = false
                        val rootPath = projectRoot.toNioPath()
                        val total = bindings.size

                        indicator.text = "Analyzing $total binding(s)..."
                        val plan =
                            buildExecutionPlan(
                                rootPath = rootPath,
                                bindings = bindings,
                                isCancelled = { indicator.isCanceled },
                                onProgress = { completed, count ->
                                    if (completed % 500 == 0 || completed == count) {
                                        indicator.fraction = (completed.toDouble() / count) * 0.2
                                        indicator.text2 = "Checked $completed of $count..."
                                    }
                                },
                            )

                        if (indicator.isCanceled) return

                        val executable = plan.filter { it !is PlannedAction.Skip }
                        skippedCount = plan.size - executable.size

                        if (executable.isEmpty()) return

                        indicator.text = "Applying ${executable.size} binding(s)..."
                        val parallelism = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(1)
                        @OptIn(ExperimentalCoroutinesApi::class)
                        val dispatcher = Dispatchers.IO.limitedParallelism(parallelism)
                        val completed = java.util.concurrent.atomic.AtomicInteger(0)

                        val results = runBlocking {
                            executable
                                .map { action ->
                                    async(dispatcher) {
                                        if (indicator.isCanceled) return@async false
                                        val ok = executePlannedAction(rootPath, action).success
                                        if (ok) {
                                            ProjectPathPolicy.resolve(rootPath, action.g4pPath)
                                                ?.let(successfulG4pPaths::add)
                                        }
                                        val done = completed.incrementAndGet()
                                        if (done % 200 == 0 || done == executable.size) {
                                            indicator.fraction = 0.2 + (done.toDouble() / executable.size) * 0.75
                                            indicator.text2 = "Processed $done of ${executable.size}..."
                                        }
                                        ok
                                    }
                                }
                                .map { deferred ->
                                    try {
                                        deferred.await()
                                    } catch (_: CancellationException) {
                                        false
                                    }
                                }
                        }

                        successCount = results.count { it }
                        failCount = results.count { !it }
                    }

                    override fun onSuccess() {
                        val msg = buildString {
                            append("Done: $successCount applied")
                            if (failCount > 0) append(", $failCount failed")
                            if (skippedCount > 0) append(", $skippedCount skipped")
                        }

                        if (successCount == 0) {
                            setProcessing(project, false)
                            updateStatus(project, msg)
                            return
                        }

                        updateStatus(project, "$msg. Updating project tree...")
                        finishApply(project, msg)
                    }

                    private fun finishApply(project: Project, msg: String): Unit =
                        refreshBindingProjectTree(
                            project = project,
                            successfulG4pPaths = successfulG4pPaths,
                            msg = msg,
                            onFinished = { setProcessing(project, false) },
                            updateStatus = { status -> updateStatus(project, status) },
                        )

                    override fun onCancel() {
                        val cancelMsg = "Cancelled. $successCount applied before cancel."
                        if (successCount > 0) {
                            finishApply(project, cancelMsg)
                        } else {
                            setProcessing(project, false)
                            updateStatus(project, cancelMsg)
                        }
                    }

                    override fun onThrowable(error: Throwable) {
                        setProcessing(project, false)
                        updateStatus(project, "Error: ${error.message}")
                    }
                },
            )
    }

    private fun clearEditor(project: Project) {
        val result =
            Messages.showYesNoDialog(
                project,
                "Clear all staged bindings?\n\nThis only clears the staging area - applied bindings are not affected.",
                "Clear Staging Area",
                Messages.getQuestionIcon(),
            )

        if (result == Messages.YES) {
            setEditorContent(project, PLACEHOLDER_TEXT)
        }
    }

    private fun getEditorContent(project: Project): String {
        val editor = editors[project] ?: return ""
        return editor.document.text
    }

    private fun setEditorContent(project: Project, content: String) {
        val editor = editors[project] ?: return
        ApplicationManager.getApplication().runWriteAction {
            @Suppress("UsePropertyAccessSyntax") editor.document.setText(content)
        }
        persistContent(project, content)
    }

    private fun loadPersistedContent(project: Project): String {
        val properties = PropertiesComponent.getInstance(project)
        return properties.getValue(STORAGE_KEY) ?: PLACEHOLDER_TEXT
    }

    private fun persistContent(project: Project, content: String) {
        val properties = PropertiesComponent.getInstance(project)
        properties.setValue(STORAGE_KEY, content)
    }
}
