package com.antlr4plus.listener.documentListener

import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.util.rethrowIfControlFlow
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

class DocumentListenerImpl(private val project: Project) : DocumentListener, Disposable {

    private val cs = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var debounceJob: Job? = null
    private val debounceDelayMs = 400L

    @Volatile private var disposed = false

    override fun documentChanged(event: DocumentEvent) {
        if (disposed || project.isDisposed || IntentionPreviewUtils.isIntentionPreviewActive()) return
        val vFile = FileDocumentManager.getInstance().getFile(event.document) ?: return
        val extension = vFile.extension?.lowercase()
        if (extension != "g4" && extension != "g4p") return
        debounceJob?.cancel()

        // Invalidate results immediately, before the debounce delay.
        val previewService =
            try {
                project.service<PreviewService>()
            } catch (failure: Exception) {
                rethrowIfControlFlow(failure)
                null
            }
        if (previewService == null || !previewService.isEnabled || previewService.isAutoRefreshPaused) return
        val pendingGeneration = previewService.invalidateCurrentProcessing()

        debounceJob = cs.launch {
            delay(debounceDelayMs.milliseconds)

            if (disposed) return@launch

            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed) return@invokeLater
                if (previewService.isCurrentProcessing(pendingGeneration)) {
                    if (!previewService.isEnabled || previewService.isAutoRefreshPaused) return@invokeLater

                    // Refresh the active input when any grammar changes, including imported
                    // grammars.
                    val fileToProcess =
                        when {
                            vFile.extension == "g4p" -> vFile
                            vFile.extension == "g4" -> {
                                val currentG4p = previewService.state.g4pInputFile
                                currentG4p ?: vFile
                            }
                            else -> vFile
                        }

                    previewService.launchProcessing(fileToProcess, forceRefresh = true)
                }
            }
        }
    }

    override fun dispose() {
        disposed = true
        debounceJob?.cancel()
        cs.cancel()
    }
}
