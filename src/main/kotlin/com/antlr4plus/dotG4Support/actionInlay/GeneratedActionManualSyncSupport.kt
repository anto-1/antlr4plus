package com.antlr4plus.dotG4Support.actionInlay

import com.antlr4plus.parsing.actionMap.ActionLocationMap
import com.antlr4plus.parsing.actionMap.ActionLocationMapService
import com.antlr4plus.parsing.actionMap.actionRangeEndExclusive
import com.antlr4plus.parsing.actionMap.containsActionOffset
import com.antlr4plus.previewEditor.GeneratedPreviewEditorSupport
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import java.io.File

internal object GeneratedActionManualSyncSupport {

    data class SyncTarget(
        val generatedHighlighter: RangeHighlighter,
        val grammarFile: File,
        val grammarDocument: Document,
        val grammarRange: TextRange,
        val entry: ActionLocationMap.Entry,
        val ownerG4pPath: String?,
    )

    fun resolveSyncTarget(project: Project, editor: Editor): SyncTarget? {
        GeneratedPreviewEditorSupport.resolveBackingIoFile(editor.document) ?: return null
        val highlighter = findApplicableHighlighter(project, editor) ?: return null
        val grammarFile = highlighter.getUserData(GeneratedActionRegionHighlighter.GRAMMAR_FILE_KEY) ?: return null
        val entry = highlighter.getUserData(GeneratedActionRegionHighlighter.ACTION_ENTRY_KEY) ?: return null
        val currentGrammarDocument =
            highlighter
                .getUserData(GeneratedActionRegionHighlighter.GRAMMAR_VIRTUAL_FILE_KEY)
                ?.takeIf { it.isValid }
                ?.let { FileDocumentManager.getInstance().getDocument(it) }
        val grammarDocument =
            currentGrammarDocument
                ?: highlighter.getUserData(GeneratedActionRegionHighlighter.GRAMMAR_DOCUMENT_KEY)
                ?: return null
        val grammarRange = resolveGrammarRange(highlighter, entry, grammarDocument)
        val ownerG4pPath = highlighter.getUserData(GeneratedActionRegionHighlighter.OWNER_G4P_PATH_KEY)

        return SyncTarget(
            generatedHighlighter = highlighter,
            grammarFile = grammarFile,
            grammarDocument = grammarDocument,
            grammarRange = grammarRange,
            entry = entry,
            ownerG4pPath = ownerG4pPath,
        )
    }

    fun syncCurrentTarget(project: Project, editor: Editor): Boolean {
        val target = resolveSyncTarget(project, editor) ?: return false
        val generatedHighlighter = target.generatedHighlighter
        if (!generatedHighlighter.isValid) return false

        val generatedStart = generatedHighlighter.startOffset.coerceIn(0, editor.document.textLength)
        val generatedEnd = generatedHighlighter.endOffset.coerceIn(generatedStart, editor.document.textLength)
        val generatedText = editor.document.getText(TextRange(generatedStart, generatedEnd))

        val grammarDocument = target.grammarDocument

        val grammarStart = target.grammarRange.startOffset.coerceIn(0, grammarDocument.textLength)
        val grammarEnd = target.grammarRange.endOffset.coerceIn(grammarStart, grammarDocument.textLength)
        val currentGrammarText = grammarDocument.getText(TextRange(grammarStart, grammarEnd))
        val newText = mergeGeneratedBodyIntoGrammar(target.entry, currentGrammarText, generatedText)
        if (currentGrammarText == newText) return false

        val mapService = project.getService(ActionLocationMapService::class.java) ?: return false
        mapService.markGeneratedSyncPending(target.grammarFile, target.ownerG4pPath)

        WriteCommandAction.runWriteCommandAction(
            project,
            "Sync Generated Code to Grammar",
            null,
            {
                grammarDocument.replaceString(grammarStart, grammarEnd, newText)
            },
        )
        return true
    }

    private fun resolveGrammarRange(
        highlighter: RangeHighlighter,
        entry: ActionLocationMap.Entry,
        grammarDocument: Document,
    ): TextRange {
        val grammarMarker = highlighter.getUserData(GeneratedActionRegionHighlighter.GRAMMAR_MARKER_KEY)
        if (grammarMarker != null && grammarMarker.isValid && grammarMarker.document === grammarDocument) {
            return TextRange(
                grammarMarker.startOffset.coerceAtLeast(0),
                grammarMarker.endOffset.coerceAtLeast(grammarMarker.startOffset),
            )
        }

        val start = entry.grammarBodyRange.first.coerceAtLeast(0)
        val end = actionRangeEndExclusive(entry.grammarBodyRange).coerceAtLeast(start)
        return TextRange(start, end)
    }

    private fun findApplicableHighlighter(project: Project, editor: Editor): RangeHighlighter? {
        val markupModel = DocumentMarkupModel.forDocument(editor.document, project, false) ?: return null
        val candidates =
            markupModel.allHighlighters.filter { highlighter ->
                highlighter.getUserData(GeneratedActionRegionHighlighter.OUR_HIGHLIGHTER_KEY) == true &&
                    highlighter.isValid
            }

        if (editor.selectionModel.hasSelection()) {
            val selectionStart = editor.selectionModel.selectionStart
            val selectionEnd = editor.selectionModel.selectionEnd
            return candidates.singleOrNull { highlighter ->
                containsSelection(
                    rangeStart = highlighter.startOffset,
                    rangeEndExclusive = highlighter.endOffset,
                    selectionStart = selectionStart,
                    selectionEndExclusive = selectionEnd,
                )
            }
        }

        val caretOffset = editor.caretModel.offset
        return candidates.singleOrNull { highlighter ->
            containsActionOffset(
                startOffset = highlighter.startOffset,
                endOffsetExclusive = highlighter.endOffset,
                offset = caretOffset,
            )
        }
    }

    private fun containsSelection(
        rangeStart: Int,
        rangeEndExclusive: Int,
        selectionStart: Int,
        selectionEndExclusive: Int,
    ): Boolean =
        selectionStart <= selectionEndExclusive &&
            rangeStart != rangeEndExclusive &&
            selectionStart >= rangeStart &&
            selectionEndExclusive <= rangeEndExclusive

    private fun mergeGeneratedBodyIntoGrammar(
        entry: ActionLocationMap.Entry,
        currentGrammarText: String,
        generatedText: String,
    ): String {
        val normalizedGeneratedText = normalizeGeneratedBodyForGrammar(entry, generatedText)
        val currentCoreRange = findNonWhitespaceRange(currentGrammarText) ?: return normalizedGeneratedText
        val generatedCore = trimBoundaryWhitespace(normalizedGeneratedText)
        return currentGrammarText.substring(0, currentCoreRange.first) +
            generatedCore +
            currentGrammarText.substring(currentCoreRange.last + 1)
    }

    private fun normalizeGeneratedBodyForGrammar(
        entry: ActionLocationMap.Entry,
        text: String,
    ): String =
        when (entry.kind) {
            "Semantic Predicate" -> stripTrailingSemicolons(text)
            else -> text
        }

    private fun stripTrailingSemicolons(text: String): String {
        var suffixStart = text.length
        while (suffixStart > 0 && text[suffixStart - 1].isWhitespace()) {
            suffixStart--
        }

        var bodyEnd = suffixStart
        while (bodyEnd > 0 && text[bodyEnd - 1] == ';') {
            bodyEnd--
        }

        if (bodyEnd == suffixStart) {
            return text
        }

        return text.substring(0, bodyEnd) + text.substring(suffixStart)
    }

    private fun trimBoundaryWhitespace(text: String): String {
        var start = 0
        while (start < text.length && text[start].isWhitespace()) {
            start++
        }

        var end = text.length
        while (end > start && text[end - 1].isWhitespace()) {
            end--
        }

        return text.substring(start, end)
    }

    private fun findNonWhitespaceRange(text: String): IntRange? {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return null
        val endExclusive = text.indexOfLast { !it.isWhitespace() } + 1
        return start until endExclusive
    }
}
