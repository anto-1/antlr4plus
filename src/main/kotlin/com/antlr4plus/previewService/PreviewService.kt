package com.antlr4plus.previewService

import com.antlr4plus.codeGen.GenAntlrCode
import com.antlr4plus.codeGen.preparePreviewAntlrArguments
import com.antlr4plus.compat.compatIsTrusted
import com.antlr4plus.compat.compatRestart
import com.antlr4plus.dotG4Support.highlighting.PluginHighlightLifecycle
import com.antlr4plus.editor.EditorAutoScroll
import com.antlr4plus.editor.EditorUtils
import com.antlr4plus.editor.clearCaretTokenHighlight
import com.antlr4plus.editor.updateCaretTokenHighlight
import com.antlr4plus.error.ComposeAntlrErrorConsolePanel
import com.antlr4plus.error.ComposeCompilerConsolePanel
import com.antlr4plus.filebinding.FileBindingService
import com.antlr4plus.listener.ParsingResultSelectionListener
import com.antlr4plus.notifications.NotificationsUtils
import com.antlr4plus.parsing.ANTLRToolListenerImpl
import com.antlr4plus.parsing.CaseChangingStrategy
import com.antlr4plus.parsing.CompiledParsingService
import com.antlr4plus.parsing.GrammarDependencyFiles
import com.antlr4plus.parsing.GrammarLanguageDetector
import com.antlr4plus.parsing.GrammarWarningsService
import com.antlr4plus.parsing.ParsingResult
import com.antlr4plus.parsing.ParsingUtils
import com.antlr4plus.parsing.PreviewParser
import com.antlr4plus.parsing.TrustedFilesManager
import com.antlr4plus.parsing.readInputText
import com.antlr4plus.preview.AltLabelTextProvider
import com.antlr4plus.preview.getTreeSelectionOffsets
import com.antlr4plus.preview.hierarchy.HierarchyViewer
import com.antlr4plus.preview.tree.BuchheimWalkerLayout
import com.antlr4plus.preview.tree.UberTreeViewer
import com.antlr4plus.settings.GrammarProperties
import com.antlr4plus.state.PreviewState
import com.antlr4plus.state.ProcessingStatus
import com.antlr4plus.statistics.ComposeProfilerPanel
import com.antlr4plus.timeline.AllTokenCapturingLexerInterpreter
import com.antlr4plus.timeline.ComposeTokenTimelinePanel
import com.antlr4plus.timeline.TokenTimelineBuilder
import com.antlr4plus.util.GeneratedWorkspaceFileOps
import com.antlr4plus.util.rethrowFatalOrCancellation
import com.antlr4plus.util.rethrowIfControlFlow
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.psi.PsiManager
import com.intellij.ui.content.Content
import com.intellij.util.messages.Topic
import com.intellij.util.ui.tree.TreeUtil
import java.io.File
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.antlr.v4.Tool
import org.antlr.v4.runtime.tree.Tree

@Service(Service.Level.PROJECT)
class PreviewService(val project: Project) : Disposable {
    val cs = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var shouldBuildTree = true
    private var shouldBuildHierarchy = true
    private val previewArtifactCache = PreviewGeneratedArtifactPolicy.SessionCache()

    @Volatile
    var isEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    val idleGeneration = invalidateCurrentProcessing(ProcessingStatus.Idle)

                    // Markup can outlive preview state and be shared by split editors; clear it
                    // before resetUI.
                    PluginHighlightLifecycle.clearAfterDeactivation(project)

                    // Timeline cleanup needs the active input reference that resetUI clears.
                    tokenTimelinePanel?.clearHighlightingState()

                    resetUI(idleGeneration, ProcessingStatus.Idle, clearInput = true)

                    val warningsService = GrammarWarningsService.getInstance(project)
                    warningsService.clearAll()

                    previewArtifactCache.clear()
                    project
                        .getService(
                            com.antlr4plus.parsing.actionMap.ActionLocationMapService::class.java,
                        )
                        .clearAll()
                    CompiledParsingService.clearAllSessionState()

                    triggerReAnnotationForAllOpenGrammarFiles()
                } else {
                    // Bindings may have changed while the plugin was disabled.
                    project.getService(FileBindingService::class.java).refreshAllDecorations()
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || !isEnabled) return@invokeLater
                        val fileToProcess = findSelectedG4pFile()
                        if (fileToProcess != null) {
                            launchProcessing(fileToProcess, forceRefresh = true)
                        }
                    }
                }
                ApplicationManager.getApplication().invokeLater {
                    project.messageBus.syncPublisher(PLUGIN_TOGGLE_TOPIC).onPluginToggled(value)
                    ProjectView.getInstance(project).refresh()
                }
            }
        }

    /** Pauses document-triggered refreshes; resuming schedules the current preview. */
    @Volatile
    var isAutoRefreshPaused: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value && isEnabled) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || !isEnabled || isAutoRefreshPaused) return@invokeLater
                        val fileToProcess = state.g4pInputFile
                        if (fileToProcess != null) {
                            launchProcessing(fileToProcess, forceRefresh = true)
                        }
                    }
                }
                ApplicationManager.getApplication().invokeLater {
                    project.messageBus.syncPublisher(AUTO_REFRESH_TOGGLE_TOPIC).onAutoRefreshToggled(!value)
                }
            }
        }

    init {
        // This service may be requested before startup initializes the Compose runtime.
        com.antlr4plus.compose.ComposeFallbackLoader.ensureComposeAvailable()

        // Use markup rather than editor selection so typing does not replace the highlighted token.
        com.intellij.openapi.editor.EditorFactory.getInstance()
            .eventMulticaster
            .addCaretListener(
                object : com.intellij.openapi.editor.event.CaretListener {
                    override fun caretPositionChanged(
                        event: com.intellij.openapi.editor.event.CaretEvent,
                    ) {
                        if (!isEnabled) return
                        val editor = event.editor
                        val g4pFile = state.g4pInputFile ?: return
                        val editorFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
                        if (editorFile.path != g4pFile.path) return
                        val panel = tokenTimelinePanel ?: return
                        val tokens = panel.getTokensForExport()
                        if (tokens.isNullOrEmpty()) return
                        val offset = editor.caretModel.offset
                        panel.stopBreathing()
                        panel.selectTokenAtOffset(offset)
                        if (activePane == "Lexer") {
                            val ps = project.service<com.antlr4plus.settings.ProjectSettingsService>().state
                            if (ps.cosmeticTokenHighlight || ps.gutterTokenIndicator) {
                                updateCosmeticTokenHighlight(
                                    editor,
                                    tokens,
                                    offset,
                                    ps.cosmeticTokenHighlight,
                                    ps.gutterTokenIndicator,
                                )
                            } else {
                                clearCaretTokenHighlight(editor, CARET_TOKEN_HIGHLIGHT_KEY)
                            }
                        } else {
                            clearCaretTokenHighlight(editor, CARET_TOKEN_HIGHLIGHT_KEY)
                        }
                    }
                },
                project,
            )

        project.messageBus
            .connect(this)
            .subscribe(
                PARSING_COMPLETION_TOPIC,
                object : ParsingCompletionListener {
                    override fun onParsingCompleted(previewState: PreviewState) {
                        val processingId = currentProcessingId()
                        val g4pFile = previewState.g4pInputFile ?: return
                        val panel = tokenTimelinePanel ?: return
                        val tokens = panel.getTokensForExport()
                        if (tokens.isNullOrEmpty()) return
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || !isCurrentProcessing(processingId)) return@invokeLater
                            if (state.g4pInputFile?.path != g4pFile.path) return@invokeLater
                            val fileEditorManager = FileEditorManager.getInstance(project)
                            val editors = fileEditorManager.getEditors(g4pFile)
                            for (fe in editors) {
                                if (fe is TextEditor) {
                                    val editor = fe.editor
                                    val offset = editor.caretModel.offset
                                    val ps2 =
                                        project
                                            .service<
                                                com.antlr4plus.settings.ProjectSettingsService,
                                            >()
                                            .state
                                    if (ps2.cosmeticTokenHighlight || ps2.gutterTokenIndicator) {
                                        updateCosmeticTokenHighlight(
                                            editor,
                                            tokens,
                                            offset,
                                            ps2.cosmeticTokenHighlight,
                                            ps2.gutterTokenIndicator,
                                        )
                                    }
                                    panel.selectTokenAtOffset(offset)
                                }
                            }
                        }
                    }
                },
            )

        // Retry the active preview when the project becomes trusted.
        @Suppress("UnstableApiUsage")
        project.messageBus
            .connect(this)
            .subscribe(
                com.intellij.ide.trustedProjects.TrustedProjectsListener.TOPIC,
                object : com.intellij.ide.trustedProjects.TrustedProjectsListener {
                    @Suppress("UnstableApiUsage")
                    override fun onProjectTrusted(project: Project) {
                        if (project != this@PreviewService.project) return
                        if (!isEnabled) return
                        l.info("Project trusted; auto-triggering preview for active file")
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || !isEnabled) return@invokeLater
                            val fileToProcess = state.g4pInputFile ?: findSelectedG4pFile()
                            if (fileToProcess != null) {
                                launchProcessing(fileToProcess, forceRefresh = true)
                            }
                        }
                    }
                },
            )
    }

    var state = PreviewState(project)
    var uber: UberTreeViewer? = null
    private val sessionLifecycle = PreviewSessionLifecycle()
    private val lastProcessingId: Long
        get() = sessionLifecycle.currentProcessingId()

    @Volatile var activePane: String = "Profiler"

    private val hierarchyListener =
        object : ParsingResultSelectionListener {
            override fun onParserRuleSelected(tree: Tree?) {
                onHierarchyElementSelected(tree)
            }
        }
    var hierarchy: HierarchyViewer? = null
        set(value) {
            if (field != value) {
                field?.removeParsingResultSelectionListener(hierarchyListener)
                field = value
                value?.addParsingResultSelectionListener(hierarchyListener)
            }
        }

    var composeProfilerPanel: ComposeProfilerPanel? = null
    var tokenTimelinePanel: ComposeTokenTimelinePanel? = null

    var toolWindow: ToolWindow? = null
    var antlrConsolePanel: ComposeAntlrErrorConsolePanel? = null
    var compilerConsolePanel: ComposeCompilerConsolePanel? = null
    var runtimeConsolePanel: com.antlr4plus.error.ComposeRuntimeConsolePanel? = null
    var antlrConsoleContent: Content? = null

    @Volatile private var currentJob: Job? = null

    private val previewGenerationMutex = kotlinx.coroutines.sync.Mutex()
    private val statusProcessingOwner = ThreadLocal<Long?>()

    /** Invalidates pending results, cancels work, and closes any visible transient UI. */
    fun invalidateCurrentProcessing(): Long = invalidateCurrentProcessing(status = null)

    private fun invalidateCurrentProcessing(status: ProcessingStatus?): Long {
        val invalidation = sessionLifecycle.invalidate(status)
        state.grammarFile?.path?.let(CompiledParsingService::invalidateCache)
        currentJob?.cancel()
        currentJob = null
        dispatchStatus("", invalidation.statusUpdate)
        val idleGeneration = invalidation.snapshot.processingId
        val cleanup = cleanup@{
            if (project.isDisposed) return@cleanup
            if (invalidation.completionAction == PreviewSessionLifecycle.CompletionAction.SHOW_PROFILER) {
                project.messageBus
                    .syncPublisher(RUNTIME_CHANGE_PENDING_TOPIC)
                    .onRuntimeChangeCompleted(invalidation.invalidatedProcessingId)
            }
            if (sessionLifecycle.isCurrent(idleGeneration)) {
                state.status = invalidation.snapshot.status
            }
            project.messageBus
                .syncPublisher(DEPENDENCY_RESOLUTION_TOPIC)
                .onResolutionCompleted(invalidation.invalidatedProcessingId)
        }
        if (ApplicationManager.getApplication().isDispatchThread) cleanup()
        else ApplicationManager.getApplication().invokeLater(cleanup)
        return idleGeneration
    }

    fun cancelCurrentJob() {
        if (currentJob?.isActive != true) {
            // A delayed click from a disappearing overlay must not turn a completed preview into
            // Cancelled.
            return
        }
        val grammarName = state.grammarFile?.name
        val grammarPath = state.grammarFile?.path
        val cancellationId = invalidateCurrentProcessing(ProcessingStatus.Cancelled)
        antlrConsolePanel?.appendLine("Preview cancelled by user", isError = false)

        if (grammarPath != null) {
            antlrConsolePanel?.appendLine("Invalidated cache for: $grammarName", isError = false)
        }

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && sessionLifecycle.isCurrent(cancellationId)) {
                project.messageBus.syncPublisher(JOB_CANCELLED_TOPIC).onJobCancelled()
            }
        }
    }

    fun publishStatus(
        message: String,
        processingId: Long? = null,
    ) {
        val ownerId = processingId ?: statusProcessingOwner.get() ?: lastProcessingId
        val update = sessionLifecycle.progress(ownerId) ?: return
        dispatchStatus(message, update)
    }

    private fun dispatchStatus(message: String, update: PreviewSessionLifecycle.StatusUpdate) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && sessionLifecycle.shouldDeliver(update)) {
                project.messageBus.syncPublisher(STATUS_UPDATE_TOPIC).onStatusUpdate(message, update.isLoading)
            }
        }
    }

    /** Commits model status and closes progress for one generation. Returns its success action. */
    private fun finishProcessing(
        processingId: Long,
        status: ProcessingStatus,
        message: String = "",
    ): Boolean {
        val transition = sessionLifecycle.finish(processingId, status) ?: return false
        state.status = status
        dispatchStatus(message, transition.statusUpdate)
        if (transition.completionAction == PreviewSessionLifecycle.CompletionAction.SHOW_PROFILER) {
            project.messageBus.syncPublisher(RUNTIME_CHANGE_PENDING_TOPIC).onRuntimeChangeCompleted(processingId)
        }
        return status == ProcessingStatus.Success &&
            transition.completionAction == PreviewSessionLifecycle.CompletionAction.SHOW_PROFILER
    }

    internal fun currentProcessingId(): Long = lastProcessingId

    internal fun statusOwnerProcessingId(): Long = statusProcessingOwner.get() ?: lastProcessingId

    internal fun isCurrentProcessing(processingId: Long): Boolean = sessionLifecycle.isCurrent(processingId)

    internal fun appendAntlrLine(
        message: String,
        isError: Boolean = true,
        processingId: Long = statusOwnerProcessingId(),
    ) {
        if (isCurrentProcessing(processingId)) antlrConsolePanel?.appendLine(message, isError)
    }

    internal fun appendCompilerLine(
        message: String,
        processingId: Long = statusOwnerProcessingId(),
    ) {
        if (isCurrentProcessing(processingId)) compilerConsolePanel?.appendLine(message)
    }

    internal fun clearRuntimeConsole(processingId: Long = statusOwnerProcessingId()) {
        if (isCurrentProcessing(processingId)) runtimeConsolePanel?.clear()
    }

    internal fun runtimeOutputSink(
        processingId: Long = statusOwnerProcessingId(),
    ): ((String) -> Unit)? {
        if (!isCurrentProcessing(processingId) || runtimeConsolePanel == null) return null
        return { line ->
            if (isCurrentProcessing(processingId)) runtimeConsolePanel?.appendLine(line)
        }
    }

    internal fun appendRuntimeLine(
        message: String,
        processingId: Long = statusOwnerProcessingId(),
    ) {
        if (isCurrentProcessing(processingId)) runtimeConsolePanel?.appendLine(message)
    }

    internal fun beginDependencyResolution(
        processingId: Long,
        message: String,
        onResolveClicked: () -> Unit,
    ): Boolean {
        if (!sessionLifecycle.moveTo(processingId, ProcessingStatus.WaitingForVerification)) return false
        state.status = ProcessingStatus.WaitingForVerification
        project.messageBus
            .syncPublisher(DEPENDENCY_RESOLUTION_TOPIC)
            .onResolutionNeeded(processingId, message, onResolveClicked)
        return true
    }

    internal fun completeDependencyResolution(processingId: Long) {
        if (sessionLifecycle.moveTo(processingId, ProcessingStatus.Loading)) {
            state.status = ProcessingStatus.Loading
        }
        project.messageBus.syncPublisher(DEPENDENCY_RESOLUTION_TOPIC).onResolutionCompleted(processingId)
    }

    fun launchProcessing(g4pFile: VirtualFile?, forceRefresh: Boolean = false) {
        launchProcessingOnEdt(g4pFile, forceRefresh, startRuleName = null, runtimeChange = null)
    }

    /** Starts a refresh whose successful completion should return the tool window to Profiler. */
    fun launchProcessingAfterRuntimeChange(g4pFile: VirtualFile, runtime: String) {
        launchProcessingOnEdt(
            g4pFile,
            forceRefresh = true,
            startRuleName = null,
            runtimeChange = runtime,
        )
    }

    fun setStartRuleAndReparse(ruleName: String) {
        val g4pFile = state.g4pInputFile ?: return
        launchProcessingOnEdt(
            g4pFile,
            forceRefresh = true,
            startRuleName = ruleName,
            runtimeChange = null,
        )
    }

    private fun launchProcessingOnEdt(
        g4pFile: VirtualFile?,
        forceRefresh: Boolean,
        startRuleName: String?,
        runtimeChange: String?,
    ) {
        val launch = {
            if (!project.isDisposed) {
                launchProcessingInternal(g4pFile, forceRefresh, startRuleName, runtimeChange)
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            launch()
        } else {
            ApplicationManager.getApplication().invokeLater(launch)
        }
    }

    private fun launchProcessingInternal(
        g4pFile: VirtualFile?,
        forceRefresh: Boolean,
        startRuleName: String?,
        runtimeChange: String?,
    ) {
        if (g4pFile == null) {
            val idleGeneration = invalidateCurrentProcessing(ProcessingStatus.Idle)
            tokenTimelinePanel?.clearHighlightingState()
            state.g4pInputFile?.let { inputFile ->
                FileEditorManager.getInstance(project).getEditors(inputFile).filterIsInstance<TextEditor>().forEach {
                    clearCaretTokenHighlight(it.editor, CARET_TOKEN_HIGHLIGHT_KEY)
                }
            }
            resetUI(idleGeneration, ProcessingStatus.Idle)
            if (sessionLifecycle.isCurrent(idleGeneration)) {
                project.messageBus.syncPublisher(FILE_SWITCHED_TOPIC).onFileSwitched(state)
                project.messageBus.syncPublisher(PARSING_COMPLETION_TOPIC).onParsingCompleted(state)
            }
            return
        }

        if (!isEnabled) {
            return
        }

        // ProjectView refresh can repeat the selection; forced refreshes must still process edits.
        if (
            !forceRefresh &&
                state.g4pInputFile?.path == g4pFile.path &&
                (state.status == ProcessingStatus.Loading || state.status is ProcessingStatus.Success)
        ) {
            return
        }

        val completionAction =
            if (runtimeChange != null) {
                PreviewSessionLifecycle.CompletionAction.SHOW_PROFILER
            } else {
                PreviewSessionLifecycle.CompletionAction.NONE
            }
        val start = sessionLifecycle.begin(completionAction)
        val id = start.processingId
        project.messageBus
            .syncPublisher(DEPENDENCY_RESOLUTION_TOPIC)
            .onResolutionCompleted(start.supersededProcessingId)
        if (
            start.supersededCompletionAction == PreviewSessionLifecycle.CompletionAction.SHOW_PROFILER &&
                completionAction != PreviewSessionLifecycle.CompletionAction.SHOW_PROFILER
        ) {
            project.messageBus
                .syncPublisher(RUNTIME_CHANGE_PENDING_TOPIC)
                .onRuntimeChangeCompleted(start.supersededProcessingId)
        }
        if (runtimeChange != null) {
            project.messageBus.syncPublisher(RUNTIME_CHANGE_PENDING_TOPIC).onRuntimeChangePending(id, runtimeChange)
        }
        if (currentJob?.isActive == true) {
            state.grammarFile?.path?.let(CompiledParsingService::invalidateCache)
        }
        currentJob?.cancel()

        // Clear the old input highlights before resetUI drops its references.
        val oldFile = state.g4pInputFile
        if (oldFile != null && oldFile.path != g4pFile.path) {
            tokenTimelinePanel?.clearHighlightingState()
            val fem = FileEditorManager.getInstance(project)
            for (fe in fem.getEditors(oldFile)) {
                if (fe is TextEditor) {
                    val editor = fe.editor
                    clearCaretTokenHighlight(editor, CARET_TOKEN_HIGHLIGHT_KEY)
                }
            }
        }

        // Reprocessing the same input must not pull focus away from the grammar editor.
        if (oldFile != null && oldFile.path != g4pFile.path) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && lastProcessingId == id) {
                    val fem = FileEditorManager.getInstance(project)
                    fem.closeFile(oldFile)
                    fem.openFile(g4pFile, false)
                }
            }
        } else if (oldFile == null) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && lastProcessingId == id) {
                    FileEditorManager.getInstance(project).openFile(g4pFile, false)
                }
            }
        }

        resetUI(id, ProcessingStatus.Loading, inputFile = g4pFile)
        publishStatus("Loading Preview", processingId = id)
        resolveBindingAndQueueProcessing(g4pFile, startRuleName, id)
    }

    private fun resolveBindingAndQueueProcessing(
        g4pFile: VirtualFile,
        startRuleName: String?,
        processingId: Long,
    ) {
        currentJob = cs.launch {
            var boundGrammarFile: VirtualFile? = null
            var handedOffToProcessor = false
            try {
                // Binding resolution can update persistent VFS storage; keep it off the EDT.
                val resolvedGrammarFile = project.service<FileBindingService>().getBoundGrammar(g4pFile)
                boundGrammarFile = resolvedGrammarFile

                withContext(Dispatchers.Main) {
                    if (project.isDisposed || lastProcessingId != processingId) return@withContext

                    if (state.g4pInputFile?.path != g4pFile.path) {
                        state.g4pInputFile = g4pFile
                    }

                    if (resolvedGrammarFile == null) {
                        finishProcessing(processingId, ProcessingStatus.Idle)
                        project.messageBus.syncPublisher(FILE_SWITCHED_TOPIC).onFileSwitched(state)
                        return@withContext
                    }

                    state.grammarFile = resolvedGrammarFile
                    project.messageBus.syncPublisher(FILE_SWITCHED_TOPIC).onFileSwitched(state)

                    if (startRuleName != null) {
                        state.startRuleName = startRuleName
                        state.isStartRuleExplicit = true
                    }
                    updateTreeViewerSettings()

                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed && lastProcessingId == processingId) {
                            ProjectView.getInstance(project).refresh()
                        }
                    }
                    queueProcessingTask(g4pFile, resolvedGrammarFile, processingId)
                    handedOffToProcessor = true
                }
            } catch (_: CancellationException) {
                terminateProcessingIfActive(
                    processingId,
                    ProcessingStatus.Cancelled,
                    boundGrammarFile?.path,
                )
                return@launch
            } catch (failure: Throwable) {
                rethrowFatalOrCancellation(failure)
                l.warn("Binding resolution failed", failure)
                terminateProcessingIfActive(
                    processingId,
                    ProcessingStatus.Failed,
                    boundGrammarFile?.path,
                    "Failed to resolve grammar binding: ${failure.message ?: failure::class.java.simpleName}",
                )
                return@launch
            }

            // A new generation or an unbound input is already terminal. Anything else is a bug,
            // so close the progress stream instead of leaving the overlay spinning forever.
            if (!handedOffToProcessor) {
                terminateProcessingIfActive(
                    processingId,
                    ProcessingStatus.Failed,
                    boundGrammarFile?.path,
                    "Preview stopped before grammar processing could start.",
                )
            }
        }
    }

    private fun queueProcessingTask(g4pFile: VirtualFile, boundGrammarFile: VirtualFile, id: Long) {
        currentJob = cs.launch {
            try {
                withContext(Dispatchers.Main) {
                    if (lastProcessingId == id) {
                        completeDependencyResolution(id)
                    }
                }

                // Selection may have changed while this coroutine was suspended for EDT work.
                if (lastProcessingId != id) {
                    l.info(
                        "Processing $id aborted after UI cleanup - superseded by processing $lastProcessingId",
                    )
                    return@launch
                }

                withContext(statusProcessingOwner.asContextElement(id)) {
                    processFile(g4pFile, boundGrammarFile, id)
                }
            } catch (_: CancellationException) {
                terminateProcessingIfActive(id, ProcessingStatus.Cancelled, boundGrammarFile.path)
                return@launch
            } catch (failure: Throwable) {
                rethrowFatalOrCancellation(failure)
                l.warn("Processing failed", failure)
                terminateProcessingIfActive(
                    id,
                    ProcessingStatus.Failed,
                    boundGrammarFile.path,
                    "Preview processing failed: ${failure.message ?: failure::class.java.simpleName}",
                )
                return@launch
            }

            terminateProcessingIfActive(
                id,
                ProcessingStatus.Failed,
                boundGrammarFile.path,
                "Preview processing ended without producing a result.",
            )
        }
    }

    /** Guarantees that an owned active generation cannot leave its progress UI open. */
    private suspend fun terminateProcessingIfActive(
        processingId: Long,
        status: ProcessingStatus,
        grammarPathToInvalidate: String? = null,
        diagnostic: String? = null,
    ) {
        withContext(NonCancellable + Dispatchers.Main) {
            if (project.isDisposed || !sessionLifecycle.isActive(processingId)) return@withContext
            grammarPathToInvalidate?.let(CompiledParsingService::invalidateCache)
            finishProcessing(processingId, status)
            diagnostic?.let { appendAntlrLine(it, processingId = processingId) }
        }
    }

    private fun resetUI(
        processingId: Long,
        status: ProcessingStatus,
        inputFile: VirtualFile? = null,
        clearInput: Boolean = false,
    ) {
        val resetAction = reset@{
            if (project.isDisposed || !sessionLifecycle.isCurrent(processingId)) return@reset
            uber?.resetData()
            hierarchy?.resetData()
            composeProfilerPanel?.resetData()
            tokenTimelinePanel?.resetData()
            antlrConsolePanel?.clear()
            state.reset()
            if (clearInput) state.g4pInputFile = null else if (inputFile != null) state.g4pInputFile = inputFile
            state.status = status
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            resetAction()
        } else {
            ApplicationManager.getApplication().invokeLater(resetAction)
        }
    }

    private suspend fun processFile(
        g4pFile: VirtualFile,
        boundGrammarFile: VirtualFile,
        processingId: Long,
    ) {
        l.info(
            "processFile started: g4pFile=${g4pFile.path}, grammarFile=${boundGrammarFile.path}, processingId=$processingId",
        )
        yield()
        if (!verifyProjectTrust(processingId)) return

        publishStatus("Loading Preview", processingId = processingId)

        yield()
        // Publish the loaded parser and lexer together to avoid mixing concurrent requests.
        if (!genStateGrammars(state, boundGrammarFile, processingId)) {
            withContext(Dispatchers.Main) {
                if (lastProcessingId == processingId) {
                    antlrConsolePanel?.appendLine(
                        "Failed to load grammar: ${boundGrammarFile.name}",
                    )
                    finishProcessing(processingId, ProcessingStatus.Failed)
                }
            }
            return
        }

        yield()
        if (!verifyProjectTrust(processingId)) return

        val request = capturePreviewRequest(g4pFile, boundGrammarFile, processingId) ?: return

        val languageDetection = GrammarLanguageDetector.detectTargetLanguage(boundGrammarFile, request.grammarCharset)

        if (!languageDetection.isJavaCompatible) {
            val warningMessage = languageDetection.getWarningMessage()
            appendAntlrLine(
                warningMessage ?: "Grammar contains non-Java target code. Preview not supported.",
                processingId = processingId,
            )
            withContext(Dispatchers.Main) {
                if (lastProcessingId == processingId) {
                    uber?.resetData()
                    project.messageBus.syncPublisher(SWITCH_PANE_TOPIC).onSwitchPane("AntlrConsole")
                    finishProcessing(processingId, ProcessingStatus.Failed)
                }
            }
            return
        }

        val selectedLanguage = request.selectedLanguage
        val isNonJavaLanguageSelected =
            !selectedLanguage.isNullOrBlank() && !selectedLanguage.equals("Java", ignoreCase = true)
        if (isNonJavaLanguageSelected) {
            val compatibilityResult =
                GrammarLanguageDetector.checkTargetLanguageCompatibility(
                    boundGrammarFile,
                    selectedLanguage,
                    request.grammarCharset,
                )
            if (!compatibilityResult.isCompatible) {
                appendAntlrLine(
                    "Warning: ${compatibilityResult.warningMessage}",
                    processingId = processingId,
                )
            }
        }

        yield()

        val parserGrammarFile = File(boundGrammarFile.path)
        val lexerGrammarFile = request.lexerGrammarFile?.let { File(it.path) }
        val grammarDependencyFiles =
            currentGrammarDependencyFiles(
                parserGrammarFile,
                lexerGrammarFile,
                request.grammar,
                request.lexerGrammar,
            )
        // Cache inspection and generation share one lock so a cancelled request cannot leave a
        // replacement request inspecting the same workspace while ANTLR is still writing it.
        val outputDirPath = previewGenerationMutex.withLock {
            val cachedOutputDir = canReuseCompiledClasses(request, grammarDependencyFiles)
            if (cachedOutputDir != null) {
                l.info("Reusing cached compiled classes, skipping code generation")
                publishStatus("Loading Preview...", processingId = processingId)
                cachedOutputDir
            } else {
                // Code generation checks its source inventory and dependency snapshot before reuse.
                val previewRuntime = request.previewRuntime
                publishStatus("Generating\n$previewRuntime Preview...", processingId = processingId)
                runInterruptible(Dispatchers.IO) {
                    genAntlrCode(request, showStatus = false, forceRegenerate = false)
                }
            }
        }
        if (outputDirPath == null) {
            AllTokenCapturingLexerInterpreter.clearCache()
            l.warn("Preview code generation failed; skipping compilation and parsing")
            withContext(Dispatchers.Main) {
                if (lastProcessingId == processingId) {
                    uber?.resetData()
                    finishProcessing(
                        processingId,
                        ProcessingStatus.Failed,
                        message = "Generation failed",
                    )
                }
            }
            return
        }
        AllTokenCapturingLexerInterpreter.clearCache()

        yield()
        // Read document text to include unsaved input edits.
        val inputText = readInputText(g4pFile)

        yield()
        if (inputText.isBlank()) {
            appendAntlrLine(
                "Input file is empty: ${g4pFile.name}",
                isError = false,
                processingId = processingId,
            )
            withContext(Dispatchers.Main) {
                if (lastProcessingId == processingId) {
                    uber?.resetData()
                    finishProcessing(processingId, ProcessingStatus.Failed)
                }
            }
            return
        }

        if (request.isLexerOnlyMode) {
            yield()
            processLexerOnlyPreview(request, inputText)
            return
        }

        yield()
        val start = System.nanoTime()
        val generatedCodeDir = File(outputDirPath)
        val result =
            ParsingUtils.parseText(
                request.grammar,
                request.lexerGrammar,
                request.startRuleName,
                boundGrammarFile,
                inputText,
                project,
                generatedCodeDir,
                null,
                request.caseChangingStrategy,
                request.previewRuntime,
                CompiledParsingService.PreviewCompilationContext(
                    processingId = processingId,
                    inputFile = request.g4pFile,
                    grammarCharset = request.grammarCharset,
                    lexerGrammarFile = request.lexerGrammarFile?.let { File(it.path) },
                    generateListener = "-no-listener" !in request.codeGenerationArgs,
                    generateVisitor = "-visitor" in request.codeGenerationArgs,
                    boundGrammarPath = request.grammarFile.path,
                ),
            )
        val duration = System.nanoTime() - start

        yield()

        // Capture Swing references and font metrics on the EDT before background layout.
        var fontSnapshot: com.antlr4plus.preview.tree.TreeViewer.FontMetricsSnapshot? = null
        var treeViewerRef: com.antlr4plus.preview.tree.TreeViewer? = null
        var hierarchyRef: HierarchyViewer? = null
        if (result != null) {
            withContext(Dispatchers.Main) {
                if (shouldBuildTree) {
                    treeViewerRef = uber?.treeViewer
                    fontSnapshot = treeViewerRef?.captureFontMetrics()
                }
                if (shouldBuildHierarchy) {
                    hierarchyRef = hierarchy
                }
            }
        }

        yield()

        var presentation: PreviewPresentation? = null
        if (result != null) {
            publishStatus("Building tree layout", processingId = processingId)

            presentation =
                PreviewComputation.build(
                    request = request,
                    parsingResult = result,
                    inputText = inputText,
                    treeViewer = treeViewerRef,
                    fontMetrics = fontSnapshot,
                    hierarchyViewer = hierarchyRef,
                    buildTree = shouldBuildTree,
                    buildHierarchy = shouldBuildHierarchy,
                )
        }

        yield()

        withContext(Dispatchers.Main) {
            if (lastProcessingId == processingId) {
                state.parsingResult = result
                if (result == null) {
                    project.messageBus.syncPublisher(SWITCH_PANE_TOPIC).onSwitchPane("AntlrConsole")
                    finishProcessing(processingId, ProcessingStatus.Failed)
                } else {
                    updateTreeViewerWithPrecomputed(
                        state,
                        result,
                        presentation?.treeLayout,
                        presentation?.subtreeBounds,
                        presentation?.hierarchyTree,
                    )
                    composeProfilerPanel?.setProfilerData(state, duration, inputText)

                    state.tokenTimeline = presentation?.tokenTimeline
                    tokenTimelinePanel?.setTimelineData(presentation?.tokenTimeline, state)

                    val switchToProfiler = finishProcessing(processingId, ProcessingStatus.Success)
                    project.messageBus.syncPublisher(PARSING_COMPLETION_TOPIC).onParsingCompleted(state)
                    DaemonCodeAnalyzer.getInstance(project).compatRestart("ANTLR4+ parsing completed")
                    ProjectView.getInstance(project).refresh()

                    if (switchToProfiler) {
                        project.messageBus.syncPublisher(SWITCH_PANE_TOPIC).onSwitchPane("Profiler")
                    }
                }
            }
        }

        // Refresh VFS off the EDT to avoid blocking UI work.
        @Suppress("SENSELESS_COMPARISON")
        if (outputDirPath != null) {
            cs.launch(Dispatchers.IO) {
                val localFs = LocalFileSystem.getInstance()
                localFs.refreshAndFindFileByPath(outputDirPath)?.let { outputVf ->
                    if (outputVf.isDirectory) {
                        outputVf.refresh(true, true)
                    }
                }
            }
        }
    }

    /** Builds the token timeline and lexing metrics for grammars without a parser. */
    private suspend fun processLexerOnlyPreview(request: PreviewRequest, inputText: String) {
        val processingId = request.processingId
        if (!verifyProjectTrust(processingId)) return

        yield()
        val start = System.nanoTime()

        val timeline =
            request.lexerGrammar?.let { lexer ->
                val effectiveStrategy =
                    CaseChangingStrategy.getEffectiveStrategy(
                        lexer,
                        request.caseChangingStrategy,
                    )
                TokenTimelineBuilder.buildTimeline(lexer, inputText, effectiveStrategy)
            }
        val duration = System.nanoTime() - start

        yield()
        withContext(Dispatchers.Main) {
            if (lastProcessingId == processingId) {
                state.tokenTimeline = timeline
                state.parsingResult = null

                if (timeline == null) {
                    antlrConsolePanel?.appendLine("Failed to tokenize input with lexer grammar.")
                    project.messageBus.syncPublisher(SWITCH_PANE_TOPIC).onSwitchPane("AntlrConsole")
                    finishProcessing(processingId, ProcessingStatus.Failed)
                } else {
                    tokenTimelinePanel?.setTimelineData(timeline, state)

                    uber?.resetData()
                    hierarchy?.resetData()

                    composeProfilerPanel?.setLexerOnlyData(state, duration, timeline.tokens.size, inputText)

                    val switchToProfiler = finishProcessing(processingId, ProcessingStatus.Success)
                    project.messageBus.syncPublisher(PARSING_COMPLETION_TOPIC).onParsingCompleted(state)
                    DaemonCodeAnalyzer.getInstance(project).compatRestart("ANTLR4+ lexer-only parsing completed")
                    ProjectView.getInstance(project).refresh()

                    if (switchToProfiler) {
                        project.messageBus.syncPublisher(SWITCH_PANE_TOPIC).onSwitchPane("Profiler")
                    } else {
                        project.messageBus.syncPublisher(SWITCH_PANE_TOPIC).onSwitchPane("Lexer")
                    }
                }
            }
        }
    }

    fun getParsingResultForG4PFile(file: VirtualFile): ParsingResult? {
        if (state.g4pInputFile?.path == file.path) {
            return state.parsingResult
        }
        return null
    }

    fun getEditorForFile(virtualFile: VirtualFile): Editor? {
        val fileEditorManager = FileEditorManager.getInstance(project)

        for (openFile in fileEditorManager.openFiles) {
            if (openFile == virtualFile) {
                val fileEditor = fileEditorManager.getSelectedEditor(virtualFile)
                if (fileEditor is TextEditor) {
                    return fileEditor.editor
                }
            }
        }
        return null
    }

    private fun genAntlrCode(
        request: PreviewRequest,
        showStatus: Boolean = false,
        forceRegenerate: Boolean = false,
    ): String? {
        if (!project.compatIsTrusted()) return null
        val file = request.grammarFile
        var outputDirPath: String?
        val configuredGrammarCharset = request.grammarCharset

        val args = request.codeGenerationArgs.toMutableList()
        val sourcePath = request.sourcePath
        val fullyQualifiedInputFileName = "$sourcePath${File.separator}${file.name}"
        val antlrArgs = preparePreviewAntlrArguments(args, fullyQualifiedInputFileName)

        val previewRuntime = request.previewRuntime
        val useKotlinPreview = previewRuntime == "Kotlin"

        val languageArgIndex = antlrArgs.indexOfFirst { it.startsWith("-Dlanguage=") }
        if (useKotlinPreview) {
            if (languageArgIndex != -1) {
                antlrArgs[languageArgIndex] = "-Dlanguage=Kotlin"
                l.info("Preview generation: Set -Dlanguage=Kotlin for Kotlin preview runtime")
            } else {
                antlrArgs.add("-Dlanguage=Kotlin")
                l.info("Preview generation: Added -Dlanguage=Kotlin for Kotlin preview runtime")
            }
        } else {
            if (languageArgIndex != -1) {
                val removedLanguage = antlrArgs[languageArgIndex].substringAfter("-Dlanguage=")
                antlrArgs.removeAt(languageArgIndex)
                l.info(
                    "Preview generation: Removed -Dlanguage=$removedLanguage, generating Java for preview",
                )
            }
        }

        val targetOutputDir = request.targetOutputDir
        val oIndex = antlrArgs.indexOf("-o")
        if (oIndex != -1) {
            antlrArgs[oIndex + 1] = targetOutputDir
        } else {
            antlrArgs.add("-o")
            antlrArgs.add(targetOutputDir)
        }

        // Resolve imports relative to the grammar source directory.
        val targetLibDir =
            request.targetLibDir
                ?: run {
                    appendAntlrLine(
                        "Invalid library directory: it must stay within the project.",
                        processingId = request.processingId,
                    )
                    return null
                }
        val libIndex = antlrArgs.indexOf("-lib")
        if (libIndex != -1) {
            antlrArgs[libIndex + 1] = targetLibDir
        } else {
            antlrArgs.add("-lib")
            antlrArgs.add(targetLibDir)
        }

        val outputDir = File(targetOutputDir)
        val parserGrammarFile = File(file.path)
        val lexerGrammarFile = request.lexerGrammarFile?.let { File(it.path) }
        val artifactPlan =
            PreviewGeneratedArtifactPolicy.buildPlan(
                parserGrammarFile = parserGrammarFile,
                lexerGrammarFile = lexerGrammarFile,
                charset = configuredGrammarCharset,
                sourceExtension = if (useKotlinPreview) "kt" else "java",
                generateListener = "-no-listener" !in antlrArgs,
                generateVisitor = "-visitor" in antlrArgs,
                packageName = request.packageName,
            )
        val grammarDependencyFiles =
            currentGrammarDependencyFiles(
                parserGrammarFile,
                lexerGrammarFile,
                request.grammar,
                request.lexerGrammar,
            )

        if (!forceRegenerate && outputDir.exists() && outputDir.isDirectory) {
            // Require all sources for the current grammar and listener/visitor settings.
            if (
                previewArtifactCache.hasReusableSources(
                    outputDir,
                    artifactPlan,
                    grammarDependencyFiles,
                ) &&
                    PreviewGeneratedArtifactPolicy.requiredSourcesIn(outputDir, artifactPlan).all {
                        TrustedFilesManager.getInstance(project).isGeneratedByUs(it)
                    }
            ) {
                l.info(
                    "Reusing existing output artifacts in $targetOutputDir " +
                        "(${artifactPlan.requiredSourceNames.size} required files)",
                )
                // Cached output still needs action maps for navigation, highlighting and edit sync.
                try {
                    refreshActionLocationMaps(request, targetOutputDir, useKotlinPreview)
                } catch (t: Throwable) {
                    rethrowFatalOrCancellation(t)
                    l.warn(
                        "Failed to refresh action location map for ${file.name} on cache hit: ${t.message}",
                        t,
                    )
                }
                return targetOutputDir
            }
        }

        val warningsService = GrammarWarningsService.getInstance(project)
        warningsService.clearWarnings(fullyQualifiedInputFileName)
        request.lexerGrammarFile?.let { lexerFile ->
            warningsService.clearWarnings(lexerFile.path)
            warningsService.clearWarnings(lexerFile.name)
        }

        if (showStatus) {
            val languageName = if (useKotlinPreview) "Kotlin" else "Java"
            publishStatus("Generating\n$languageName Code...")
        }

        val antlr = Tool(antlrArgs.toTypedArray())
        val antlrListener =
            ANTLRToolListenerImpl(antlr, antlrConsolePanel, project) {
                isCurrentProcessing(request.processingId)
            }
        antlr.addListener(antlrListener)

        // Save generated documents before codegen to avoid simultaneous document/disk conflicts.
        saveGeneratedParserDocumentsForGrammar(file)

        previewArtifactCache.invalidate(outputDir)
        val actionMapService =
            project.getService(
                com.antlr4plus.parsing.actionMap.ActionLocationMapService::class.java,
            )
        actionMapService.invalidate(parserGrammarFile)
        lexerGrammarFile
            ?.takeUnless { it.absolutePath == parserGrammarFile.absolutePath }
            ?.let(actionMapService::invalidate)
        if (outputDir.exists()) {
            l.info("Preview regeneration: replacing generated artifacts in $targetOutputDir")
            if (!PreviewGeneratedArtifactPolicy.cleanForRegeneration(outputDir, artifactPlan)) {
                l.warn("Could not safely clean preview output directory: $targetOutputDir")
                return null
            }
        }

        // Reject generation if dependency contents change while ANTLR is reading them.
        val dependencySnapshotAtGenerationStart =
            PreviewGeneratedArtifactPolicy.captureDependencySnapshot(
                artifactPlan,
                grammarDependencyFiles,
            )
                ?: run {
                    l.warn("Could not capture preview grammar dependencies before generation")
                    return null
                }

        try {
            antlr.processGrammarsOnCommandLine()
            outputDirPath = antlr.outputDirectory
            warningsService.triggerReAnnotation(fullyQualifiedInputFileName)
            request.lexerGrammarFile?.let { lexerFile ->
                warningsService.triggerReAnnotation(lexerFile.path)
                warningsService.triggerReAnnotation(lexerFile.name)
            }
            if (antlrListener.errors.isNotEmpty() || antlr.numErrors > 0) {
                val summary =
                    antlrListener.errors.firstOrNull()?.lineSequence()?.firstOrNull()
                        ?: "ANTLR reported ${antlr.numErrors} generation error(s)"
                l.warn("Preview generation failed for ${file.path}: $summary")
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || !isCurrentProcessing(request.processingId)) return@invokeLater
                    Notifications.Bus.notify(
                        Notification(
                            GenAntlrCode.GROUP_DISPLAY_ID,
                            "Can't generate parser for ${file.name}",
                            summary,
                            NotificationType.ERROR,
                        ),
                        project,
                    )
                }
                return null
            }
            if (
                !previewArtifactCache.recordGeneration(
                    outputDir,
                    artifactPlan,
                    grammarDependencyFiles,
                    dependencySnapshotAtGenerationStart,
                )
            ) {
                l.warn(
                    "Grammar dependencies changed during preview generation; discarding the result",
                )
                appendAntlrLine(
                    "Grammar files changed during generation. Preview generation was discarded; please retry.",
                    processingId = request.processingId,
                )
                return null
            }
            val generatedSources = PreviewGeneratedArtifactPolicy.ownedSourcesIn(outputDir, artifactPlan)
            TrustedFilesManager.getInstance(project).markAsGenerated(generatedSources)
            try {
                refreshActionLocationMaps(request, outputDirPath, useKotlinPreview)
            } catch (t: Throwable) {
                rethrowFatalOrCancellation(t)
                l.warn("Failed to refresh action location map for ${file.name}: ${t.message}", t)
            }
            return outputDirPath
        } catch (e: Throwable) {
            rethrowFatalOrCancellation(e)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || !isCurrentProcessing(request.processingId)) return@invokeLater
                Notifications.Bus.notify(
                    Notification(
                        GenAntlrCode.GROUP_DISPLAY_ID,
                        "Can't generate parser for ${file.name}",
                        e.message ?: "Unknown error",
                        NotificationType.ERROR,
                    ),
                    project,
                )
            }
            antlrListener.hasOutput = true
            return null
        }
    }

    /** Rebuilds action maps for generated parser and lexer sources; callers log nonfatal failures. */
    private fun refreshActionLocationMaps(
        request: PreviewRequest,
        outputDirPath: String?,
        useKotlinPreview: Boolean,
    ) {
        if (outputDirPath == null) return
        val grammarVf = request.grammarFile
        val outputDir = File(outputDirPath)
        if (!outputDir.isDirectory) return

        val service =
            project.getService(
                com.antlr4plus.parsing.actionMap.ActionLocationMapService::class.java,
            ) ?: return

        val expectedExt = if (useKotlinPreview) "kt" else "java"
        val grammarCharset = request.grammarCharset

        val grammarsToRefresh = mutableListOf<File>()
        grammarsToRefresh.add(File(grammarVf.path))
        request.lexerGrammarFile?.let { lexerVf ->
            val lexerFile = File(lexerVf.path)
            if (lexerFile.absolutePath != grammarVf.path) {
                grammarsToRefresh.add(lexerFile)
            }
        }

        for (g in grammarsToRefresh) {
            val grammarName = g.nameWithoutExtension
            val filesByName =
                outputDir
                    .listFiles { f ->
                        f.isFile && f.extension == expectedExt
                    }
                    ?.associateBy { it.name } ?: continue

            val candidates =
                listOf(
                        "${grammarName}Parser.$expectedExt",
                        "${grammarName}Lexer.$expectedExt",
                        "$grammarName.$expectedExt",
                    )
                    .mapNotNull(filesByName::get)

            if (candidates.isEmpty()) continue

            for (candidate in candidates) {
                service.refreshFor(g, candidate, grammarCharset)
                l.info("ActionLocationMap: refreshed for session ${g.name} -> ${candidate.name}")
            }
        }
    }

    /** Saves mapped generated-source documents on the EDT before codegen overwrites disk content. */
    private fun saveGeneratedParserDocumentsForGrammar(grammarVf: VirtualFile) {
        try {
            val service =
                project.getService(
                    com.antlr4plus.parsing.actionMap.ActionLocationMapService::class.java,
                ) ?: return
            val maps = service.getAll(File(grammarVf.path))
            if (maps.isEmpty()) return
            // Resolve VFS files on the generation thread; dispatch document access and saving to
            // the EDT.
            val generatedDocuments = maps.mapNotNull { map ->
                LocalFileSystem.getInstance().findFileByIoFile(map.generatedFile)?.let { generatedFile ->
                    map to generatedFile
                }
            }
            if (generatedDocuments.isEmpty()) return
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    for ((map, generatedFile) in generatedDocuments) {
                        val genDoc =
                            ApplicationManager.getApplication().runReadAction<
                                com.intellij.openapi.editor.Document?,
                            > {
                                FileDocumentManager.getInstance().getDocument(generatedFile)
                            } ?: continue
                        FileDocumentManager.getInstance().saveDocument(genDoc)
                        l.info(
                            "Saved generated document for ${grammarVf.name} -> ${map.generatedFile.name}",
                        )
                    }
                } catch (t: Throwable) {
                    rethrowFatalOrCancellation(t)
                    l.warn(
                        "Failed to save generated parser document for ${grammarVf.name}: ${t.message}",
                    )
                }
            }
        } catch (t: Throwable) {
            rethrowFatalOrCancellation(t)
            l.warn("Failed to dispatch save for ${grammarVf.name}: ${t.message}")
        }
    }

    /** Generates the configured target language into its language-specific output directory. */
    fun generateCodeForSelectedLanguage() {
        l.info("╔══════════════════════════════════════════════════════════════════════════════╗")
        l.info("║ generateCodeForSelectedLanguage() CALLED                                     ║")
        l.info("╚══════════════════════════════════════════════════════════════════════════════╝")
        l.info("  state.grammarFile = ${state.grammarFile}")
        l.info("  state.g4pInputFile = ${state.g4pInputFile}")
        l.info("  state.liveSettings?.language = ${state.liveSettings?.language}")
        l.info("  state.liveSettings?.outputDir = ${state.liveSettings?.outputDir}")

        val grammarFile =
            state.grammarFile
                ?: run {
                    l.warn("  grammarFile is NULL - showing notification and returning")
                    Notifications.Bus.notify(
                        Notification(
                            "ANTLR Preview",
                            "Generate Code",
                            "No grammar file is currently loaded. Open a .g4p file first.",
                            NotificationType.WARNING,
                        ),
                        project,
                    )
                    return
                }

        l.info("  grammarFile resolved: ${grammarFile.path}")
        val processingIdAtStart = currentProcessingId()
        val generationArgs =
            try {
                getCodeGenerationArgs(grammarFile).toList()
            } catch (failure: Throwable) {
                rethrowFatalOrCancellation(failure)
                Notifications.Bus.notify(
                    Notification(
                        "ANTLR Preview",
                        "Generate Code Failed",
                        "Failed to configure code generation: ${failure.message}",
                        NotificationType.ERROR,
                    ),
                    project,
                )
                return
            }
        val targetLanguage = state.liveSettings?.language?.takeIf { it.isNotBlank() } ?: "Java"

        ApplicationManager.getApplication().executeOnPooledThread {
            l.info("  [PooledThread] Starting code generation...")
            try {
                l.info("  [PooledThread] Calling GenAntlrCode.antlr()")
                val generation = GenAntlrCode.antlr(project, grammarFile, generationArgs, targetLanguage)
                if (!generation.successful) {
                    l.warn("  [PooledThread] Code generation failed; output navigation skipped")
                    return@executeOnPooledThread
                }
                l.info("  [PooledThread] GenAntlrCode.antlr() completed")

                // Navigate using the output path resolved by the generation operation.
                val effectiveOutputDir = generation.outputDirectory
                l.info("  [PooledThread] Resolving output directory: $effectiveOutputDir")
                val outputVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(effectiveOutputDir)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || !isCurrentProcessing(processingIdAtStart)) return@invokeLater
                    if (outputVirtualFile != null && outputVirtualFile.isValid && outputVirtualFile.isDirectory) {
                        l.info("  [UI] Found output directory, selecting in ProjectView")
                        ProjectView.getInstance(project).select(null, outputVirtualFile, true)
                    } else {
                        l.warn(
                            "  [UI] Output directory not found or not a directory: $effectiveOutputDir",
                        )
                    }
                }
                l.info(
                    "╔══════════════════════════════════════════════════════════════════════════════╗",
                )
                l.info(
                    "║ generateCodeForSelectedLanguage() COMPLETED SUCCESSFULLY                     ║",
                )
                l.info(
                    "╚══════════════════════════════════════════════════════════════════════════════╝",
                )
            } catch (e: Exception) {
                rethrowIfControlFlow(e)
                l.error("  [PooledThread] EXCEPTION during code generation:", e)
                ApplicationManager.getApplication().invokeLater {
                    Notifications.Bus.notify(
                        Notification(
                            "ANTLR Preview",
                            "Generate Code Failed",
                            "Failed to generate code: ${e.message}",
                            NotificationType.ERROR,
                        ),
                        project,
                    )
                }
            }
        }
    }

    /** Deletes the configured output directory after boundary and protected-input checks. */
    fun deleteOutputDirectory() {
        l.info("deleteOutputDirectory() called")
        val profileFile =
            state.g4pInputFile
                ?: run {
                    Notifications.Bus.notify(
                        Notification(
                            "ANTLR Preview",
                            "Delete Output",
                            "No .g4p file is currently loaded.",
                            NotificationType.WARNING,
                        ),
                        project,
                    )
                    return
                }
        val contentRoot = profileFile.parent ?: return
        val protectedPaths = listOfNotNull(profileFile.path, state.grammarFile?.path, state.lexerGrammarFile?.path)
        val configuredOutputDir = state.liveSettings?.outputDir

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val outputDir =
                    GrammarProperties.resolveOutputDirForDeletion(
                        project,
                        contentRoot,
                        configuredOutputDir,
                        protectedPaths,
                    )
                if (outputDir == null) {
                    ApplicationManager.getApplication().invokeLater {
                        Notifications.Bus.notify(
                            Notification(
                                "ANTLR Preview",
                                "Delete Output Blocked",
                                "The configured output directory is unsafe to delete.",
                                NotificationType.WARNING,
                            ),
                            project,
                        )
                    }
                    return@executeOnPooledThread
                }
                val outputDirPath = outputDir.path
                l.info("Resolved output directory for deletion: $outputDirPath")

                if (!outputDir.exists()) {
                    ApplicationManager.getApplication().invokeLater {
                        Notifications.Bus.notify(
                            Notification(
                                "ANTLR Preview",
                                "Delete Output",
                                "Output directory does not exist: $outputDirPath",
                                NotificationType.INFORMATION,
                            ),
                            project,
                        )
                    }
                    return@executeOnPooledThread
                }

                val projectRoot = project.basePath?.let { File(it) }
                val relativePath =
                    if (projectRoot != null) {
                        try {
                            projectRoot.toPath().relativize(outputDir.toPath()).toString() + "/"
                        } catch (e: Exception) {
                            rethrowIfControlFlow(e)
                            outputDir.name + "/"
                        }
                    } else {
                        outputDir.name + "/"
                    }

                val success = GeneratedWorkspaceFileOps.deleteTree(outputDir.toPath())
                l.info("  Delete result: $success")

                // Refresh VFS on the pooled thread before updating the UI.
                val parentPath = outputDir.parentFile?.absolutePath
                if (parentPath != null) {
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(parentPath)?.refresh(false, true)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        NotificationsUtils.showToast(project, "Deleted: $relativePath")
                    } else {
                        NotificationsUtils.showToast(project, "Failed to delete: $relativePath")
                    }
                }
            } catch (e: Exception) {
                rethrowIfControlFlow(e)
                l.error("Failed to delete output directory", e)
                ApplicationManager.getApplication().invokeLater {
                    Notifications.Bus.notify(
                        Notification(
                            "ANTLR Preview",
                            "Delete Output Failed",
                            "Failed to delete output directory: ${e.message}",
                            NotificationType.ERROR,
                        ),
                        project,
                    )
                }
            }
        }
    }

    /** Returns reusable output after checking session fingerprints, generated sources and class hashes. */
    private fun canReuseCompiledClasses(
        request: PreviewRequest,
        grammarDependencyFiles: Collection<File>,
    ): String? {
        val parserGrammarFile = File(request.grammarFile.path)
        val lexerGrammarFile = request.lexerGrammarFile?.let { File(it.path) }
        val trustedFilesManager = TrustedFilesManager.getInstance(project)

        val parserGrammarVf = LocalFileSystem.getInstance().findFileByIoFile(parserGrammarFile)
        if (parserGrammarVf == null) {
            l.debug("Parser grammar missing, regeneration needed")
            return null
        }

        val previewRuntime = request.previewRuntime
        val isKotlinRuntime = previewRuntime == "Kotlin"
        val targetOutputDir = request.targetOutputDir

        val outputDir = File(targetOutputDir)
        if (!outputDir.exists() || !outputDir.isDirectory) {
            l.debug("Output directory doesn't exist, regeneration needed: $targetOutputDir")
            return null
        }

        val sourceExtension = if (isKotlinRuntime) "kt" else "java"
        val charset = request.grammarCharset
        val artifactPlan =
            PreviewGeneratedArtifactPolicy.buildPlan(
                parserGrammarFile = parserGrammarFile,
                lexerGrammarFile = lexerGrammarFile,
                charset = charset,
                sourceExtension = sourceExtension,
                generateListener = "-no-listener" !in request.codeGenerationArgs,
                generateVisitor = "-visitor" in request.codeGenerationArgs,
                packageName = request.packageName,
            )
        val sourceFiles = PreviewGeneratedArtifactPolicy.requiredSourcesIn(outputDir, artifactPlan)
        if (sourceFiles.size != artifactPlan.requiredSourceNames.size) {
            l.debug(
                "Missing generated $sourceExtension files for ${parserGrammarFile.name}; " +
                    "expected ${artifactPlan.requiredSourceNames}, found ${sourceFiles.map { it.name }}",
            )
            return null
        }

        if (
            !previewArtifactCache.hasReusableSources(
                outputDir,
                artifactPlan,
                grammarDependencyFiles,
            )
        ) {
            l.debug("Session dependency fingerprint changed or is missing; regeneration needed")
            return null
        }

        for (sourceFile in sourceFiles) {
            if (!trustedFilesManager.isGeneratedByUs(sourceFile)) {
                l.debug(
                    "Generated source file not marked as ours or modified: ${sourceFile.name}, regeneration needed",
                )
                return null
            }
        }

        val compileOutputDir = File(targetOutputDir, "compiled")
        when (val checkResult = trustedFilesManager.checkClassFiles(compileOutputDir)) {
            is TrustedFilesManager.ClassFileCheckResult.Valid -> {
                l.info(
                    "All dependency hashes match, reusing compiled classes from: $targetOutputDir",
                )
                return targetOutputDir
            }
            is TrustedFilesManager.ClassFileCheckResult.NoFiles -> {
                l.debug("No compiled class files found, regeneration needed")
                return null
            }
            is TrustedFilesManager.ClassFileCheckResult.UnmarkedFiles -> {
                l.debug("Unmarked class files found: ${checkResult.files}, regeneration needed")
                return null
            }
            is TrustedFilesManager.ClassFileCheckResult.TamperedFiles -> {
                l.warn("Tampered class files detected: ${checkResult.files}, regeneration needed")
                return null
            }
        }
    }

    private fun currentGrammarDependencyFiles(
        parserGrammarFile: File,
        lexerGrammarFile: File?,
        grammar: org.antlr.v4.tool.Grammar?,
        lexerGrammar: org.antlr.v4.tool.LexerGrammar?,
    ): List<File> =
        GrammarDependencyFiles.collect(
            parserGrammarFile,
            lexerGrammarFile,
            grammar,
            lexerGrammar,
        )

    override fun dispose() {
        sessionLifecycle.invalidate(ProcessingStatus.Idle)
        previewArtifactCache.clear()
        currentJob?.cancel()
        cs.cancel()
        PluginHighlightLifecycle.clearForDisposal(project)
        composeProfilerPanel?.dispose()
        state.grammarFile?.path?.let(CompiledParsingService::invalidateCache)
        state.dispose()
    }

    private suspend fun verifyProjectTrust(processingId: Long): Boolean {
        if (!project.compatIsTrusted()) {
            l.info("Project is NOT trusted; blocking preview generation")
            appendAntlrLine(
                "Preview blocked: project is not trusted. Use File → Trust Project to enable preview.",
                processingId = processingId,
            )
            withContext(Dispatchers.Main) {
                if (lastProcessingId == processingId) {
                    finishProcessing(processingId, ProcessingStatus.Failed)
                }
            }
            return false
        }
        return true
    }

    /** Captures every mutable selection/settings value before long-running generation starts. */
    private suspend fun capturePreviewRequest(
        g4pFile: VirtualFile,
        boundGrammarFile: VirtualFile,
        processingId: Long,
    ): PreviewRequest? =
        withContext(Dispatchers.Main) {
            if (project.isDisposed || lastProcessingId != processingId) return@withContext null

            ParsingUtils.autoSetStartRule(state)
            val liveSettings = state.liveSettings
            val grammarCharset =
                liveSettings
                    ?.encoding
                    ?.takeIf { it.isNotBlank() }
                    ?.let { encoding ->
                        runCatching { java.nio.charset.Charset.forName(encoding) }.getOrNull()
                    } ?: Charsets.UTF_8
            val previewRuntime = liveSettings?.previewRuntime ?: "Java"
            val sourcePath = getSourcePath(boundGrammarFile)

            PreviewRequest(
                processingId = processingId,
                g4pFile = g4pFile,
                grammarFile = boundGrammarFile,
                lexerGrammarFile = state.lexerGrammarFile,
                grammar = state.grammar,
                lexerGrammar = state.lexerGrammar,
                startRuleName = state.startRuleName,
                selectedLanguage = liveSettings?.language,
                previewRuntime = previewRuntime,
                grammarCharset = grammarCharset,
                caseChangingStrategy = liveSettings?.caseChangingStrategy ?: CaseChangingStrategy.LEAVE_AS_IS,
                codeGenerationArgs = getCodeGenerationArgs(boundGrammarFile).toList(),
                sourcePath = sourcePath,
                targetOutputDir =
                    GrammarProperties.resolvePreviewOutputDir(
                        project = project,
                        contentRoot = g4pFile.parent,
                        configuredOutputDir = liveSettings?.outputDir,
                        runtime = previewRuntime,
                    ),
                targetLibDir = GrammarProperties.resolveLibDir(state, project, sourcePath),
            )
        }

    private suspend fun genStateGrammars(
        state: PreviewState,
        boundGrammarFile: VirtualFile,
        processingId: Long,
    ): Boolean {
        val loaded =
            runInterruptible(Dispatchers.IO) {
                ParsingUtils.loadGrammars(project, boundGrammarFile)?.let { (lexer, parser) ->
                    if (lexer == null && parser == null) return@let null
                    val lexerFile = lexer?.fileName?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                    Triple(lexer, parser, lexerFile)
                }
            } ?: return false
        val (lexer, parser, lexerFile) = loaded
        val shouldAutoDetectLowercase = CaseChangingStrategy.isCaseInsensitive(lexer)

        return withContext(Dispatchers.Main) {
            if (project.isDisposed || lastProcessingId != processingId) {
                return@withContext false
            }
            state.lexerGrammar = lexer
            state.lexerGrammarFile = lexerFile
            state.grammar = parser

            // Auto-select lowercase folding only when the user has no explicit case strategy.
            if (shouldAutoDetectLowercase) {
                val currentStrategy = state.liveSettings?.caseChangingStrategy
                if (currentStrategy == null || currentStrategy == CaseChangingStrategy.LEAVE_AS_IS) {
                    state.liveSettings?.caseChangingStrategy = CaseChangingStrategy.FORCE_LOWERCASE
                    state.liveSettings?.saveToPersistence()

                    val lexerName = lexer?.name ?: "Lexer"
                    Notifications.Bus.notify(
                        Notification(
                            "ANTLR Preview",
                            "Case-Insensitive Grammar Detected",
                            "$lexerName uses caseInsensitive=true. " +
                                "Case transformation set to LowerCase automatically.",
                            NotificationType.INFORMATION,
                        ),
                        project,
                    )
                }
            }
            true
        }
    }

    private fun getCodeGenerationArgs(boundGrammarFile: VirtualFile): MutableList<String> =
        ReadAction.compute<MutableList<String>, Throwable> {
            GenAntlrCode.getANTLRArgsAsList(project, boundGrammarFile)
        }

    private fun getSourcePath(boundGrammarFile: VirtualFile): String =
        ReadAction.compute<String, Throwable> {
            GenAntlrCode.getParentDir(boundGrammarFile)
        }

    /** Installs precomputed tree data on the EDT, computing missing layouts or models as a fallback. */
    private fun updateTreeViewerWithPrecomputed(
        preview: PreviewState,
        result: ParsingResult,
        treeViewerLayout: BuchheimWalkerLayout<Tree>?,
        treeViewerSubtreeBounds: Map<Tree, java.awt.geom.Rectangle2D.Double>?,
        hierarchyWrappedTree: DefaultMutableTreeNode?,
    ) {
        updateTreeViewerSettings()
        val javaTree = result.javaTree
        (result.parser as? PreviewParser)?.let {
            val g = preview.grammar ?: return
            val provider = AltLabelTextProvider(it, g)
            if (shouldBuildTree && javaTree != null) {
                uber?.treeModel?.treeTextProvider = provider
                if (treeViewerLayout != null) {
                    uber
                        ?.treeViewer
                        ?.setTreeWithLayout(
                            javaTree,
                            treeViewerLayout,
                            precomputedSubtreeBounds = treeViewerSubtreeBounds,
                        )
                } else {
                    uber?.treeViewer?.setTree(javaTree)
                }
            }

            if (shouldBuildHierarchy && javaTree != null) {
                hierarchy?.setTreeTextProvider(provider)
                if (hierarchyWrappedTree != null) {
                    hierarchy?.setTreeWithModel(hierarchyWrappedTree, true)
                } else {
                    hierarchy?.setTree(javaTree)
                }
                hierarchy?.repaint()
            }
        }
            ?: run {
                // Prefer compiled parser rule names so labels match the parsed tree.
                val ruleNames =
                    result.ruleNames?.toList()
                        ?: preview.grammar?.getRuleNames()?.toList()
                        ?: throw IllegalStateException("Missing ruleNames")
                val vocabulary = result.vocabulary
                val kotlinVocabulary = if (result.isKotlin) vocabulary else null
                if (shouldBuildTree && javaTree != null) {
                    uber?.treeModel?.setTreeTextProvider(ruleNames, vocabulary, kotlinVocabulary)
                    if (treeViewerLayout != null) {
                        uber
                            ?.treeViewer
                            ?.setTreeWithLayout(
                                javaTree,
                                treeViewerLayout,
                                precomputedSubtreeBounds = treeViewerSubtreeBounds,
                            )
                    } else {
                        uber?.treeViewer?.setTree(javaTree)
                    }
                }

                if (shouldBuildHierarchy && javaTree != null) {
                    hierarchy?.setRuleNamesWithVocabulary(ruleNames, vocabulary, kotlinVocabulary)
                    if (hierarchyWrappedTree != null) {
                        hierarchy?.setTreeWithModel(hierarchyWrappedTree, true)
                    } else {
                        hierarchy?.setTree(javaTree)
                    }
                    hierarchy?.repaint()
                }
            }
        val hierarchyScrollPane =
            SwingUtilities.getAncestorOfClass(JScrollPane::class.java, hierarchy?.myTree) as? JScrollPane
        hierarchyScrollPane?.revalidate()
        hierarchy?.repaint()
    }

    fun updateTreeViewerSettings() {
        val viewer = uber?.treeViewer ?: return
        val uberViewer = uber ?: return
        val settings = state.liveSettings

        val modeString = settings?.treeConnectionMode
        val mode =
            when (modeString) {
                "ORTHOGONAL" -> com.antlr4plus.preview.tree.TreeViewer.ConnectionMode.ORTHOGONAL
                "DIRECT" -> com.antlr4plus.preview.tree.TreeViewer.ConnectionMode.DIRECT
                else -> com.antlr4plus.preview.tree.TreeViewer.ConnectionMode.SPLINE
            }
        viewer.setConnectionMode(mode)

        uberViewer.splineFab.isActive = (mode == com.antlr4plus.preview.tree.TreeViewer.ConnectionMode.SPLINE)
        uberViewer.orthogonalFab.isActive = (mode == com.antlr4plus.preview.tree.TreeViewer.ConnectionMode.ORTHOGONAL)
        uberViewer.directLinesFab.isActive = (mode == com.antlr4plus.preview.tree.TreeViewer.ConnectionMode.DIRECT)

        val edgeColorHex = settings?.treeEdgeColor
        if (edgeColorHex != null) {
            try {
                viewer.edgeColor = java.awt.Color.decode(edgeColorHex)
                uberViewer.edgeColorPicker.currentColor = viewer.edgeColor
            } catch (e: NumberFormatException) {}
        } else {
            val panelBg = javax.swing.UIManager.getColor("Panel.background") ?: java.awt.Color(60, 63, 65)
            val isLightTheme = (panelBg.red + panelBg.green + panelBg.blue) / 3 > 128
            val defaultEdgeColor = if (isLightTheme) java.awt.Color(153, 153, 153) else com.intellij.ui.Gray._96
            viewer.edgeColor = defaultEdgeColor
            uberViewer.edgeColorPicker.currentColor = defaultEdgeColor
        }

        val nodeColorHex = settings?.treeNodeColor
        if (nodeColorHex != null) {
            try {
                viewer.nodeTextColor = java.awt.Color.decode(nodeColorHex)
                uberViewer.nodeColorPicker.currentColor = viewer.nodeTextColor ?: java.awt.Color.decode("#808080")
            } catch (e: NumberFormatException) {}
        } else {
            val panelBg = javax.swing.UIManager.getColor("Panel.background") ?: java.awt.Color(60, 63, 65)
            val isLightTheme = (panelBg.red + panelBg.green + panelBg.blue) / 3 > 128
            val defaultNodeColor =
                if (isLightTheme) java.awt.Color.decode("#606060") else java.awt.Color.decode("#808080")
            viewer.nodeTextColor = defaultNodeColor
            uberViewer.nodeColorPicker.currentColor = defaultNodeColor
        }

        val textColorHex = settings?.treeTextColor
        if (textColorHex != null) {
            try {
                viewer.textColor = java.awt.Color.decode(textColorHex)
                uberViewer.textColorPicker.currentColor = viewer.textColor ?: java.awt.Color.WHITE
            } catch (e: NumberFormatException) {}
        } else {
            val panelBg = javax.swing.UIManager.getColor("Panel.background") ?: java.awt.Color(60, 63, 65)
            val isLightTheme = (panelBg.red + panelBg.green + panelBg.blue) / 3 > 128
            val defaultTextColor = if (isLightTheme) java.awt.Color.BLACK else java.awt.Color.decode("#BCBEC4")
            viewer.textColor = defaultTextColor
            uberViewer.textColorPicker.currentColor = defaultTextColor
        }

        val bgColorHex = settings?.treeBackgroundColor
        if (bgColorHex != null) {
            try {
                viewer.background = java.awt.Color.decode(bgColorHex)
                uberViewer.backgroundColorPicker.currentColor = viewer.background
            } catch (e: NumberFormatException) {}
        } else {
            viewer.background = javax.swing.UIManager.getColor("Panel.background") ?: java.awt.Color(60, 63, 65)
            uberViewer.backgroundColorPicker.currentColor = viewer.background
        }

        viewer.repaint()
    }

    private fun onHierarchyElementSelected(tree: Tree?) {
        val g4pFile = state.g4pInputFile ?: return
        val fileEditor =
            FileEditorManager.getInstance(project).getEditors(g4pFile).firstOrNull()
                ?: EditorUtils.getOrOpenEditorWithoutFocus(project, g4pFile)
                ?: return

        if (fileEditor !is TextEditor) return
        val editor = fileEditor.editor

        val (startIndex, stopIndex) = getTreeSelectionOffsets(tree) ?: return

        val document = editor.document
        if (startIndex < 0 || stopIndex + 1 > document.textLength) return

        runReadAction {
            val selectionModel = editor.selectionModel
            selectionModel.removeSelection()
            selectionModel.setSelection(startIndex, stopIndex + 1)
            centerSelectionStartInEditor(editor, startIndex)
        }
    }

    private fun centerSelectionStartInEditor(editor: Editor, startIndex: Int) {
        val clampedOffset = startIndex.coerceIn(0, editor.document.textLength)
        val logicalPos = editor.offsetToLogicalPosition(clampedOffset)

        val targetY = editor.logicalPositionToXY(logicalPos).y

        val viewportHeight = editor.scrollingModel.visibleArea.height

        val scrollY = targetY - viewportHeight / 2

        editor.scrollingModel.scrollVertically(scrollY.coerceAtLeast(0))
    }

    fun onUberElementSelected(tree: Tree?) {
        val g4pFile = state.g4pInputFile ?: return
        val fileEditor = FileEditorManager.getInstance(project).getEditors(g4pFile).firstOrNull() ?: return
        if (fileEditor !is TextEditor) return
        val editor = fileEditor.editor

        val (startIndex, stopIndex) = getTreeSelectionOffsets(tree) ?: return
        EditorAutoScroll.scrollEditorWithSelection(editor, startIndex, stopIndex)
    }

    @Suppress("UnresolvedPluginConfigReference")
    suspend fun showToast(project: Project? = null, message: String, durationMillis: Long = 2000) {
        val notification =
            Notification(
                "MyPluginNotificationGroup",
                "Info",
                message,
                NotificationType.INFORMATION,
            )

        Notifications.Bus.notify(notification, project)

        coroutineScope {
            delay(durationMillis.milliseconds)
            notification.expire()
        }
    }

    fun showNotification(
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION,
    ) {
        ApplicationManager.getApplication().invokeLater {
            Notifications.Bus.notify(
                Notification("ANTLR4Plus", title, content, type),
                project,
            )
        }
    }

    /** Finds the selected Project Tree input without falling back to the active editor. */
    private fun findSelectedG4pFile(): VirtualFile? {
        try {
            val projectView = ProjectView.getInstance(project)
            val currentPane = projectView.currentProjectViewPane
            val selectionPaths = currentPane?.selectionPaths
            if (selectionPaths != null && selectionPaths.size == 1) {
                val selectedNode = selectionPaths[0].lastPathComponent
                val userObject = TreeUtil.getUserObject(selectedNode)
                if (userObject is ProjectViewNode<*>) {
                    val file = userObject.virtualFile
                    if (file != null && file.extension.equals("g4p", ignoreCase = true)) {
                        return file
                    }
                }
            }
        } catch (e: Exception) {
            rethrowIfControlFlow(e)
        }
        return null
    }

    private fun triggerReAnnotationForAllOpenGrammarFiles() {
        ApplicationManager.getApplication().invokeLater {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val psiManager = PsiManager.getInstance(project)
            val daemonCodeAnalyzer = DaemonCodeAnalyzer.getInstance(project)

            for (file in fileEditorManager.openFiles) {
                val ext = file.extension?.lowercase()
                if (ext == "g4" || ext == "g4p") {
                    val psiFile = psiManager.findFile(file)
                    if (psiFile != null) {
                        daemonCodeAnalyzer.compatRestart(
                            psiFile,
                            "ANTLR4+ grammar file re-annotation",
                        )
                    }
                }
            }
        }
    }

    interface ParsingCompletionListener {
        fun onParsingCompleted(previewState: PreviewState)
    }

    interface FileSwitchedListener {
        fun onFileSwitched(previewState: PreviewState)
    }

    interface StatusUpdateListener {
        fun onStatusUpdate(message: String, isLoading: Boolean)
    }

    interface SwitchPaneListener {
        fun onSwitchPane(paneName: String)
    }

    interface RuntimeHighlightListener {
        fun onHighlightRuntime()
    }

    interface RuntimeChangePendingListener {
        fun onRuntimeChangePending(processingId: Long, newRuntime: String)

        fun onRuntimeChangeCompleted(processingId: Long) {}
    }

    interface RuntimeRevertListener {
        fun onRuntimeReverted(runtime: String)
    }

    interface PluginToggleListener {
        fun onPluginToggled(enabled: Boolean)
    }

    interface AutoRefreshToggleListener {
        fun onAutoRefreshToggled(enabled: Boolean)
    }

    interface JobCancelledListener {
        fun onJobCancelled()
    }

    /** Shows dependency conflict resolution controls in the tool window. */
    interface DependencyResolutionListener {
        fun onResolutionNeeded(processingId: Long, message: String, onResolveClicked: () -> Unit)

        fun onResolutionCompleted(processingId: Long)
    }

    companion object {
        private val l = Logger.getInstance(PreviewService::class.java)
        val CARET_TOKEN_HIGHLIGHT_KEY = com.intellij.openapi.util.Key.create<Boolean>("ANTLR4P_CARET_TOKEN_HIGHLIGHT")
        val PARSING_COMPLETION_TOPIC = Topic.create("Parsing Completion", ParsingCompletionListener::class.java)
        val FILE_SWITCHED_TOPIC = Topic.create("File Switched", FileSwitchedListener::class.java)
        val STATUS_UPDATE_TOPIC = Topic.create("Status Update", StatusUpdateListener::class.java)
        val SWITCH_PANE_TOPIC = Topic.create("Switch Pane", SwitchPaneListener::class.java)
        val RUNTIME_HIGHLIGHT_TOPIC = Topic.create("Runtime Highlight", RuntimeHighlightListener::class.java)
        val RUNTIME_CHANGE_PENDING_TOPIC =
            Topic.create("Runtime Change Pending", RuntimeChangePendingListener::class.java)
        val RUNTIME_REVERT_TOPIC = Topic.create("Runtime Revert", RuntimeRevertListener::class.java)
        val PLUGIN_TOGGLE_TOPIC = Topic.create("Plugin Toggle", PluginToggleListener::class.java)
        val AUTO_REFRESH_TOGGLE_TOPIC = Topic.create("Auto Refresh Toggle", AutoRefreshToggleListener::class.java)
        val DEPENDENCY_RESOLUTION_TOPIC =
            Topic.create("Dependency Resolution", DependencyResolutionListener::class.java)
        val JOB_CANCELLED_TOPIC = Topic.create("Job Cancelled", JobCancelledListener::class.java)

        /** Updates caret-following token markup. Call on the EDT. */
        fun updateCosmeticTokenHighlight(
            editor: Editor,
            tokens: List<com.antlr4plus.timeline.TokenWithMode>,
            offset: Int,
            showHighlight: Boolean = true,
            showGutter: Boolean = true,
        ): Unit =
            updateCaretTokenHighlight(
                editor,
                tokens,
                offset,
                showHighlight,
                showGutter,
                CARET_TOKEN_HIGHLIGHT_KEY,
            )
    }
}
