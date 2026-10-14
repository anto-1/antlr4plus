package com.antlr4plus.dotG4Support.actionInlay

import com.antlr4plus.compat.compatSetErrorStripeMarkColor
import com.antlr4plus.parsing.embedded.EmbeddedCode
import com.antlr4plus.parsing.embedded.GrammarCodeExtractor
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.util.rethrowIfControlFlow
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import java.awt.Color
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Highlights embedded code from current grammar text without requiring generated preview files. */
class EmbeddedCodeRegionHighlighter(
    private val project: Project,
    private val document: Document,
) {
    private val log = Logger.getInstance(EmbeddedCodeRegionHighlighter::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val ourHighlighters = mutableListOf<RangeHighlighter>()

    private val refreshRequestId = AtomicLong()

    @Volatile private var isDisposed = false

    private var refreshJob: Job? = null

    fun install(parent: Disposable) {
        project.messageBus
            .connect(parent)
            .subscribe(
                PreviewService.PLUGIN_TOGGLE_TOPIC,
                object : PreviewService.PluginToggleListener {
                    override fun onPluginToggled(enabled: Boolean) {
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || isDisposed) return@invokeLater
                            if (enabled) {
                                scheduleRefresh(immediate = true)
                            } else {
                                clearAll()
                            }
                        }
                    }
                },
            )

        document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (IntentionPreviewUtils.isIntentionPreviewActive()) return
                    scheduleRefresh()
                }
            },
            parent,
        )

        Disposer.register(parent) {
            isDisposed = true
            refreshRequestId.incrementAndGet()
            refreshJob?.cancel()
            scope.cancel()
            if (ApplicationManager.getApplication().isDispatchThread) {
                clearAll()
            } else {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) clearAll()
                }
            }
        }

        scheduleRefresh(immediate = true)
    }

    private fun scheduleRefresh(immediate: Boolean = false) {
        if (isDisposed || project.isDisposed) return
        refreshJob?.cancel()
        val currentRequestId = refreshRequestId.incrementAndGet()

        refreshJob = scope.launch {
            if (!immediate) {
                delay(REFRESH_DEBOUNCE_MS.milliseconds)
            }

            val previewService =
                try {
                    project.service<PreviewService>()
                } catch (failure: Throwable) {
                    rethrowIfControlFlow(failure)
                    null
                }
            if (previewService?.isEnabled == false) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || isDisposed) return@invokeLater
                    if (currentRequestId != refreshRequestId.get()) return@invokeLater
                    clearAll()
                }
                return@launch
            }

            val virtualFile = FileDocumentManager.getInstance().getFile(document) ?: return@launch
            if (!virtualFile.name.endsWith(".g4", ignoreCase = true)) return@launch

            val snapshot =
                try {
                    ApplicationManager.getApplication().runReadAction<String> {
                        document.text
                    }
                } catch (failure: Throwable) {
                    rethrowIfControlFlow(failure)
                    return@launch
                }

            val embeddedCode =
                try {
                    GrammarCodeExtractor.extract(snapshot, virtualFile.path)
                } catch (t: Throwable) {
                    rethrowIfControlFlow(t)
                    log.debug("Embedded code highlight analysis failed for ${virtualFile.name}", t)
                    null
                }

            val ranges =
                embeddedCode?.mapNotNull { block ->
                    EmbeddedCodeHighlightRangeCalculator.calculate(block, snapshot)?.let {
                        HighlightedBlock(block, it)
                    }
                } ?: emptyList()

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || isDisposed) return@invokeLater
                if (currentRequestId != refreshRequestId.get()) return@invokeLater
                repaint(ranges)
            }
        }
    }

    private fun repaint(blocks: List<HighlightedBlock>) {
        if (isDisposed) return
        clearAll()
        if (blocks.isEmpty()) return

        val markupModel = DocumentMarkupModel.forDocument(document, project, true)
        val documentLength = document.textLength
        val attributes = createActionRegionAttributes()

        for ((block, range) in blocks) {
            val startOffset = range.first.coerceIn(0, documentLength)
            val endOffsetExclusive = (range.last + 1).coerceIn(startOffset, documentLength)
            if (endOffsetExclusive <= startOffset) continue

            try {
                val highlighter =
                    markupModel.addRangeHighlighter(
                        startOffset,
                        endOffsetExclusive,
                        HighlighterLayer.SELECTION - 2,
                        attributes,
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                highlighter.putUserData(OUR_HIGHLIGHTER_KEY, true)
                highlighter.compatSetErrorStripeMarkColor(STRIPE_COLOR)
                highlighter.errorStripeTooltip = buildStripeTooltip(block)
                ourHighlighters.add(highlighter)
            } catch (t: Throwable) {
                rethrowIfControlFlow(t)
                log.debug(
                    "Failed to add embedded code highlight for ${block.contextDescription()}",
                    t,
                )
            }
        }
    }

    private fun clearAll() {
        if (ourHighlighters.isEmpty()) return

        val markupModel = DocumentMarkupModel.forDocument(document, project, false)
        val toRemove = ourHighlighters.toList()
        ourHighlighters.clear()

        for (highlighter in toRemove) {
            try {
                if (highlighter.isValid && markupModel != null) {
                    markupModel.removeHighlighter(highlighter)
                }
            } catch (failure: Throwable) {
                rethrowIfControlFlow(failure)
            }
        }
    }

    private fun buildStripeTooltip(block: EmbeddedCode): String = "Embedded code: ${block.contextDescription()}"

    private data class HighlightedBlock(
        val block: EmbeddedCode,
        val range: IntRange,
    )

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 200L

        val OUR_HIGHLIGHTER_KEY: Key<Boolean> = Key.create("antlr4plus.embedded.code.region.highlighter")

        private val STRIPE_COLOR =
            JBColor(
                Color(0x4A, 0x9E, 0xFF),
                Color(0x6B, 0xB3, 0xFF),
            )
    }
}
