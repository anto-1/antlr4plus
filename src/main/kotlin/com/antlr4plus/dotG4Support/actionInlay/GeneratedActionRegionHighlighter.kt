package com.antlr4plus.dotG4Support.actionInlay

import com.antlr4plus.compat.compatSetErrorStripeMarkColor
import com.antlr4plus.parsing.actionMap.ActionLocationMap
import com.antlr4plus.parsing.actionMap.ActionLocationMapService
import com.antlr4plus.parsing.actionMap.GeneratedSyncPreviewRefreshSupport
import com.antlr4plus.previewEditor.GeneratedPreviewEditorSupport
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.util.rethrowIfControlFlow
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Color
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Highlights mapped generated code and stores metadata for manual sync back to the grammar. */
class GeneratedActionRegionHighlighter(
    private val project: Project,
    private val document: Document,
) {
    private val log = Logger.getInstance(GeneratedActionRegionHighlighter::class.java)
    private val pairs = mutableListOf<HighlighterPair>()
    private val repaintGeneration = AtomicLong()

    @Volatile private var isDisposed = false

    @Volatile private var activeGrammarPath: String? = null

    private var activeMap: ActionLocationMap? = null

    private data class HighlighterPair(
        val generatedHighlighter: RangeHighlighter,
        var grammarMarker: RangeMarker?,
    )

    fun install(parent: Disposable) {
        project.messageBus
            .connect(parent)
            .subscribe(
                PreviewService.PLUGIN_TOGGLE_TOPIC,
                object : PreviewService.PluginToggleListener {
                    override fun onPluginToggled(enabled: Boolean) {
                        if (enabled) scheduleRepaint() else scheduleClear()
                    }
                },
            )

        project.messageBus
            .connect(parent)
            .subscribe(
                ActionLocationMapService.TOPIC,
                object : ActionLocationMapService.ActionLocationMapListener {
                    override fun mapRefreshed(grammarPath: String, map: ActionLocationMap?) {
                        if (!isAffectedBy(grammarPath, map)) return
                        if (map == null) scheduleClear() else scheduleRepaint(map)
                    }
                },
            )

        Disposer.register(parent) {
            isDisposed = true
            activeGrammarPath = null
            repaintGeneration.incrementAndGet()
            runOnEdt { clearAll() }
        }
        scheduleRepaint()
    }

    /** Rebuild uncached maps off the EDT; install session-cached markers immediately when on the EDT. */
    private fun scheduleRepaint(mapHint: ActionLocationMap? = null) {
        if (isDisposed || project.isDisposed) return
        if (project.getService(PreviewService::class.java)?.isEnabled != true) {
            scheduleClear()
            return
        }
        val generation = repaintGeneration.incrementAndGet()
        if (mapHint != null) activeGrammarPath = mapHint.grammarFile.absolutePath
        val mapService = project.getService(ActionLocationMapService::class.java) ?: return
        val generatedVf = GeneratedPreviewEditorSupport.resolveBackingFile(document)
        val extension = generatedVf?.extension?.lowercase()
        val generatedPath = generatedVf?.path
        val cachedMap =
            if (generatedPath != null && (extension == "java" || extension == "kt")) {
                mapHint?.takeIf { pathsEqual(it.generatedFile.path, generatedPath) }
                    ?: mapService.getCachedByGeneratedFile(File(generatedPath))
            } else {
                null
            }

        // Install cached markers before asynchronous lookup so they track edits made as the editor
        // opens.
        val installedCachedMap = ApplicationManager.getApplication().isDispatchThread && cachedMap != null
        if (installedCachedMap) {
            activeGrammarPath = requireNotNull(cachedMap).grammarFile.absolutePath
            repaint(
                map = cachedMap,
                grammarVf = null,
                grammarDocument = null,
                generatedName = generatedVf?.name,
                ownerG4pPath = null,
            )
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed || project.isDisposed || repaintGeneration.get() != generation) {
                return@executeOnPooledThread
            }

            val map =
                if (generatedPath != null && (extension == "java" || extension == "kt")) {
                    cachedMap ?: mapService.getByGeneratedFile(File(generatedPath))
                } else {
                    null
                }

            val grammarVf =
                if (map != null && map.entries.isNotEmpty()) {
                    LocalFileSystem.getInstance().findFileByIoFile(map.grammarFile)
                } else {
                    null
                }
            val grammarDocument = grammarVf?.let {
                ApplicationManager.getApplication().runReadAction<Document?> {
                    FileDocumentManager.getInstance().getDocument(it)
                }
            }
            val ownerG4pPath = map?.let {
                GeneratedSyncPreviewRefreshSupport.resolvePreviewOwner(
                        project,
                        it.generatedFile,
                        it.grammarFile,
                    )
                    ?.g4pFile
                    ?.path
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || isDisposed || repaintGeneration.get() != generation) {
                    return@invokeLater
                }
                if (project.getService(PreviewService::class.java)?.isEnabled != true) {
                    clearAll()
                    return@invokeLater
                }
                activeGrammarPath = map?.grammarFile?.absolutePath
                if (installedCachedMap && map != null && activeMap == map) {
                    enrichMetadata(grammarVf, grammarDocument, ownerG4pPath)
                } else {
                    repaint(map, grammarVf, grammarDocument, generatedVf?.name, ownerG4pPath)
                }
            }
        }
    }

    private fun scheduleClear() {
        val generation = repaintGeneration.incrementAndGet()
        activeGrammarPath = null
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || isDisposed || repaintGeneration.get() != generation) {
                return@invokeLater
            }
            clearAll()
        }
    }

    private fun repaint(
        map: ActionLocationMap?,
        grammarVf: VirtualFile?,
        grammarDocument: Document?,
        generatedName: String?,
        ownerG4pPath: String?,
    ) {
        clearAll()
        if (map == null || map.entries.isEmpty()) return

        val markupModel = DocumentMarkupModel.forDocument(document, project, true)
        val documentLength = document.textLength
        val attributes = createActionRegionAttributes()
        var added = 0

        for (entry in map.entries) {
            val startOffset = entry.generatedBodyRange.first.coerceIn(0, documentLength)
            val endOffsetExclusive =
                if (entry.generatedBodyRange.isEmpty()) {
                    startOffset
                } else {
                    (entry.generatedBodyRange.last + 1).coerceIn(startOffset, documentLength)
                }

            try {
                val highlighter =
                    markupModel.addRangeHighlighter(
                        startOffset,
                        endOffsetExclusive,
                        HighlighterLayer.SELECTION - 2,
                        attributes,
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                highlighter.isGreedyToLeft = true
                highlighter.isGreedyToRight = true
                highlighter.putUserData(OUR_HIGHLIGHTER_KEY, true)
                highlighter.putUserData(GRAMMAR_FILE_KEY, map.grammarFile)
                highlighter.putUserData(GRAMMAR_VIRTUAL_FILE_KEY, grammarVf)
                highlighter.putUserData(GRAMMAR_DOCUMENT_KEY, grammarDocument)
                highlighter.putUserData(OWNER_G4P_PATH_KEY, ownerG4pPath)
                highlighter.compatSetErrorStripeMarkColor(STRIPE_COLOR)
                highlighter.errorStripeTooltip = buildStripeTooltip(entry)
                val grammarMarker =
                    grammarDocument
                        ?.createRangeMarker(
                            entry.grammarBodyRange.first.coerceIn(0, grammarDocument.textLength),
                            if (entry.grammarBodyRange.isEmpty()) {
                                entry.grammarBodyRange.first.coerceIn(0, grammarDocument.textLength)
                            } else {
                                (entry.grammarBodyRange.last + 1).coerceIn(
                                    entry.grammarBodyRange.first.coerceIn(
                                        0,
                                        grammarDocument.textLength,
                                    ),
                                    grammarDocument.textLength,
                                )
                            },
                        )
                        ?.apply {
                            isGreedyToLeft = true
                            isGreedyToRight = true
                        }
                highlighter.putUserData(GRAMMAR_MARKER_KEY, grammarMarker)
                highlighter.putUserData(ACTION_ENTRY_KEY, entry)
                pairs.add(HighlighterPair(highlighter, grammarMarker))
                added++
            } catch (t: Throwable) {
                rethrowIfControlFlow(t)
                log.debug("Failed to add generated action highlight for ${entry.id}", t)
            }
        }

        activeMap = map

        log.info(
            "GeneratedActionRegionHighlighter: added $added highlight(s) for ${generatedName ?: map.generatedFile.name}",
        )
    }

    /** Adds grammar-side state without replacing generated markers that may already track edits. */
    private fun enrichMetadata(
        grammarVf: VirtualFile?,
        grammarDocument: Document?,
        ownerG4pPath: String?,
    ) {
        for (pair in pairs) {
            val highlighter = pair.generatedHighlighter
            if (!highlighter.isValid) continue
            val entry = highlighter.getUserData(ACTION_ENTRY_KEY) ?: continue

            pair.grammarMarker?.dispose()
            val grammarMarker =
                grammarDocument
                    ?.createRangeMarker(
                        entry.grammarBodyRange.first.coerceIn(0, grammarDocument.textLength),
                        if (entry.grammarBodyRange.isEmpty()) {
                            entry.grammarBodyRange.first.coerceIn(0, grammarDocument.textLength)
                        } else {
                            (entry.grammarBodyRange.last + 1).coerceIn(
                                entry.grammarBodyRange.first.coerceIn(
                                    0,
                                    grammarDocument.textLength,
                                ),
                                grammarDocument.textLength,
                            )
                        },
                    )
                    ?.apply {
                        isGreedyToLeft = true
                        isGreedyToRight = true
                    }

            pair.grammarMarker = grammarMarker
            highlighter.putUserData(GRAMMAR_VIRTUAL_FILE_KEY, grammarVf)
            highlighter.putUserData(GRAMMAR_DOCUMENT_KEY, grammarDocument)
            highlighter.putUserData(GRAMMAR_MARKER_KEY, grammarMarker)
            highlighter.putUserData(OWNER_G4P_PATH_KEY, ownerG4pPath)
        }
    }

    private fun clearAll() {
        activeMap = null
        if (pairs.isEmpty()) return

        val markupModel = DocumentMarkupModel.forDocument(document, project, false)
        val toRemove = pairs.toList()
        pairs.clear()

        for ((generatedHighlighter, grammarMarker) in toRemove) {
            try {
                if (generatedHighlighter.isValid && markupModel != null) {
                    markupModel.removeHighlighter(generatedHighlighter)
                }
            } catch (failure: Throwable) {
                rethrowIfControlFlow(failure)
            }
            try {
                grammarMarker?.dispose()
            } catch (failure: Throwable) {
                rethrowIfControlFlow(failure)
            }
        }
    }

    private fun isAffectedBy(grammarPath: String, map: ActionLocationMap?): Boolean {
        val currentFile = GeneratedPreviewEditorSupport.resolveBackingFile(document) ?: return false
        if (map != null) {
            return pathsEqual(currentFile.path, map.generatedFile.path)
        }
        return activeGrammarPath?.let { pathsEqual(it, grammarPath) } == true
    }

    private fun pathsEqual(first: String, second: String): Boolean {
        val firstNormalized = FileUtil.toCanonicalPath(File(first).absolutePath)
        val secondNormalized = FileUtil.toCanonicalPath(File(second).absolutePath)
        return FileUtil.pathsEqual(firstNormalized, secondNormalized)
    }

    private fun runOnEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater(action)
    }

    private fun buildStripeTooltip(entry: ActionLocationMap.Entry): String {
        val scopePart = if (entry.scope != null) " in ${entry.scope}" else ""
        return "Generated counterpart: ${entry.kind.lowercase()}$scopePart"
    }

    companion object {
        val OUR_HIGHLIGHTER_KEY: Key<Boolean> = Key.create("antlr4plus.generated.action.region.highlighter")
        val GRAMMAR_FILE_KEY: Key<File> = Key.create("antlr4plus.generated.action.region.highlighter.grammar.file")
        val GRAMMAR_VIRTUAL_FILE_KEY: Key<VirtualFile> =
            Key.create("antlr4plus.generated.action.region.highlighter.grammar.virtual.file")
        val GRAMMAR_DOCUMENT_KEY: Key<Document> =
            Key.create("antlr4plus.generated.action.region.highlighter.grammar.document")
        val OWNER_G4P_PATH_KEY: Key<String> =
            Key.create("antlr4plus.generated.action.region.highlighter.owner.g4p.path")
        val GRAMMAR_MARKER_KEY: Key<RangeMarker> =
            Key.create("antlr4plus.generated.action.region.highlighter.grammar.marker")
        val ACTION_ENTRY_KEY: Key<ActionLocationMap.Entry> =
            Key.create("antlr4plus.generated.action.region.highlighter.entry")

        private val STRIPE_COLOR =
            JBColor(
                Color(0x4A, 0x9E, 0xFF),
                Color(0x6B, 0xB3, 0xFF),
            )
    }
}
