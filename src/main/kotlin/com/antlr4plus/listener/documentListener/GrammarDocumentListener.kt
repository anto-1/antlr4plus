package com.antlr4plus.listener.documentListener

import com.antlr4plus.parsing.GrammarDependencyFiles
import com.antlr4plus.parsing.actionMap.ActionLocationMapService
import com.antlr4plus.previewService.PreviewService
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debounces preview refreshes after grammar edits. */
class GrammarDocumentListener(private val project: Project) : DocumentListener, Disposable {

    private val cs = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var debounceJob: Job? = null

    @Volatile private var disposed = false

    override fun documentChanged(event: DocumentEvent) {
        scheduleRefresh(event.document)
    }

    override fun bulkUpdateFinished(document: com.intellij.openapi.editor.Document) {
        // Bulk edits may suppress individual documentChanged events.
        scheduleRefresh(document)
    }

    private fun scheduleRefresh(document: com.intellij.openapi.editor.Document) {
        if (disposed || project.isDisposed || IntentionPreviewUtils.isIntentionPreviewActive()) return
        val changedFile = FileDocumentManager.getInstance().getFile(document) ?: return
        val previewService = project.service<PreviewService>()
        if (!previewService.isEnabled || previewService.isAutoRefreshPaused) return

        val state = previewService.state
        val grammarFile = state.grammarFile ?: return
        val lexerGrammarFile = state.lexerGrammarFile
        val isRelevantGrammar =
            GrammarDependencyFiles.contains(
                changedFile = java.io.File(changedFile.path),
                parserGrammarFile = java.io.File(grammarFile.path),
                lexerGrammarFile = lexerGrammarFile?.let { java.io.File(it.path) },
                parserGrammar = state.grammar,
                lexerGrammar = state.lexerGrammar,
            )
        if (!isRelevantGrammar) return

        val mapService = project.getService(ActionLocationMapService::class.java)
        if (mapService?.peekPendingGeneratedSync(java.io.File(changedFile.path)) != null) return

        debounceJob?.cancel()
        val pendingGeneration = previewService.invalidateCurrentProcessing()
        val activeInputPath = state.g4pInputFile?.path

        debounceJob = cs.launch {
            delay(DEBOUNCE_DELAY_MS.milliseconds)

            if (disposed) return@launch

            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed) return@invokeLater
                if (!previewService.isCurrentProcessing(pendingGeneration)) return@invokeLater
                if (!previewService.isEnabled || previewService.isAutoRefreshPaused) return@invokeLater
                val g4pFile = previewService.state.g4pInputFile ?: return@invokeLater
                if (g4pFile.path != activeInputPath) return@invokeLater

                // ANTLR reads the grammar from disk.
                FileDocumentManager.getInstance().saveDocument(document)
                previewService.launchProcessing(g4pFile, forceRefresh = true)
            }
        }
    }

    override fun dispose() {
        disposed = true
        debounceJob?.cancel()
        cs.cancel()
    }

    companion object {
        private const val DEBOUNCE_DELAY_MS = 500L
    }
}
