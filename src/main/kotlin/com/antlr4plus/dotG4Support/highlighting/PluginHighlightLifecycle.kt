package com.antlr4plus.dotG4Support.highlighting

import com.antlr4plus.dotG4Support.actionInlay.EmbeddedCodeRegionHighlighter
import com.antlr4plus.dotG4Support.actionInlay.GeneratedActionRegionHighlighter
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.statistics.ComposeProfilerPanel
import com.antlr4plus.timeline.ComposeTokenTimelinePanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project

/** Clears ANTLR4+ markup from all project editors, including split and generated-source editors. */
object PluginHighlightLifecycle {
    fun clearAfterDeactivation(project: Project) = clear(project, skipIfReenabled = true)

    fun clearForDisposal(project: Project) = clear(project, skipIfReenabled = false)

    private fun clear(project: Project, skipIfReenabled: Boolean) {
        val clearAction = Runnable {
            // A queued deactivation cleanup must not erase markers restored after re-enabling.
            if (
                skipIfReenabled &&
                    !project.isDisposed &&
                    project.getService(PreviewService::class.java)?.isEnabled != false
            )
                return@Runnable
            EditorFactory.getInstance()
                .allEditors
                .asSequence()
                .filter { it.project === project && !it.isDisposed }
                .forEach(::clearEditor)
        }

        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            clearAction.run()
        } else {
            application.invokeLater(clearAction)
        }
    }

    private fun clearEditor(editor: Editor) {
        val markupModel = editor.markupModel
        val owned =
            markupModel.allHighlighters.filter { highlighter ->
                highlighter.getUserData(EmbeddedCodeRegionHighlighter.OUR_HIGHLIGHTER_KEY) == true ||
                    highlighter.getUserData(GeneratedActionRegionHighlighter.OUR_HIGHLIGHTER_KEY) == true ||
                    highlighter.getUserData(ComposeTokenTimelinePanel.TOKEN_HIGHLIGHT_KEY) == true ||
                    highlighter.getUserData(PreviewService.CARET_TOKEN_HIGHLIGHT_KEY) == true ||
                    highlighter.getUserData(Highlighter.TOKEN_INFO_HIGHLIGHT_KEY) == true ||
                    highlighter.getUserData(ComposeProfilerPanel.HIGHLIGHT_G4P_INFO_KEY) != null ||
                    highlighter.getUserData(ComposeProfilerPanel.HIGHLIGHT_G4P_RULE_INFO_KEY) != null ||
                    highlighter.getUserData(ComposeProfilerPanel.HIGHLIGHT_GRAMMAR_INFO_KEY) != null
            }
        owned.forEach(markupModel::removeHighlighter)
    }
}
