package com.antlr4plus.dotG4Support.highlighting

import com.antlr4plus.statistics.ComposeProfilerPanel
import com.antlr4plus.statistics.ComposeProfilerPanel.Companion.HIGHLIGHT_G4P_INFO_KEY
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key

object Highlighter {
    val TOKEN_INFO_HIGHLIGHT_KEY: Key<Boolean> = Key.create("antlr4plus.token.info.highlighter")

    fun removeDecisionEventHighlighters(editor: Editor) {
        val markupModel = editor.markupModel
        markupModel.allHighlighters.forEach { highlighter ->
            if (
                highlighter.getUserData(HIGHLIGHT_G4P_INFO_KEY) != null ||
                    highlighter.getUserData(ComposeProfilerPanel.HIGHLIGHT_G4P_RULE_INFO_KEY) != null
            ) {
                markupModel.removeHighlighter(highlighter)
            }
        }
    }

    fun clearTokenInfoHighlighters(editor: Editor) {
        val markupModel = editor.markupModel
        for (r in markupModel.allHighlighters) {
            if (r.getUserData(TOKEN_INFO_HIGHLIGHT_KEY) == true) {
                markupModel.removeHighlighter(r)
            }
        }
    }

    fun removeHighlighters(editor: Editor, key: Key<*>) {
        val markupModel = editor.markupModel
        for (r in markupModel.allHighlighters) {
            if (r.getUserData(key) != null) {
                markupModel.removeHighlighter(r)
            }
        }
    }
}
