package com.antlr4plus.dotG4PSupport.lineMarker

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.project.Project

/**
 * Suppresses wide first-line gutter icons in .g4p inputs, including shebang run icons. Uses document markup and
 * preserves the narrow caret-token indicator.
 */
object G4PRunLineMarkerSuppressor {

    private val LOG = Logger.getInstance(G4PRunLineMarkerSuppressor::class.java)

    fun install(editor: Editor, parentDisposable: Disposable) {
        val project: Project =
            editor.project
                ?: run {
                    LOG.warn("Cannot install run-line-marker suppressor: editor has no project")
                    return
                }

        // Platform line markers belong to document markup, not editor-local markup.
        val docMarkup =
            DocumentMarkupModel.forDocument(editor.document, project, true) as? MarkupModelEx
                ?: run {
                    LOG.warn(
                        "Cannot install run-line-marker suppressor: DocumentMarkupModel is not MarkupModelEx",
                    )
                    return
                }

        // The daemon may have installed markers before this listener.
        sweepShebangGutterIcons(editor, docMarkup)

        docMarkup.addMarkupModelListener(
            parentDisposable,
            object : MarkupModelListener {
                override fun afterAdded(highlighter: RangeHighlighterEx) {
                    if (isShebangRunMarker(editor, highlighter)) {
                        // Defer removal until the platform has finished initializing the
                        // highlighter.
                        ApplicationManager.getApplication().invokeLater {
                            if (highlighter.isValid) {
                                try {
                                    docMarkup.removeHighlighter(highlighter)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                override fun attributesChanged(
                    highlighter: RangeHighlighterEx,
                    renderersChanged: Boolean,
                    fontStyleOrColorChanged: Boolean,
                ) {
                    // The renderer can be attached after the highlighter is added.
                    if (renderersChanged && isShebangRunMarker(editor, highlighter)) {
                        ApplicationManager.getApplication().invokeLater {
                            if (highlighter.isValid) {
                                try {
                                    docMarkup.removeHighlighter(highlighter)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            },
        )
    }

    private fun sweepShebangGutterIcons(editor: Editor, docMarkup: MarkupModelEx) {
        val toRemove =
            docMarkup.allHighlighters.filter { h ->
                h is RangeHighlighterEx && isShebangRunMarker(editor, h)
            }
        toRemove.forEach { h ->
            try {
                docMarkup.removeHighlighter(h)
            } catch (_: Exception) {}
        }
    }

    /** Width/line heuristic: preserves icons up to 4px wide but may match other first-line icons. */
    private fun isShebangRunMarker(editor: Editor, highlighter: RangeHighlighterEx): Boolean {
        val renderer = highlighter.gutterIconRenderer ?: return false
        val icon = renderer.icon
        if (icon.iconWidth <= 4) return false

        val doc = editor.document
        if (highlighter.startOffset < 0 || highlighter.startOffset > doc.textLength) return false
        val line = doc.getLineNumber(highlighter.startOffset)
        return line == 0
    }
}
