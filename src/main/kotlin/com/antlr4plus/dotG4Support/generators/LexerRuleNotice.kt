package com.antlr4plus.dotG4Support.generators

import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/** Shows the extra definitions included in a grammar edit before the user applies it. */
internal class LexerRuleNotice(private val showEmptyHint: Boolean = false) : JScrollPane() {
    private val message =
        JTextArea(4, 50).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JLabel().font
            isOpaque = false
        }

    init {
        setViewportView(message)
        border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
        isOpaque = false
        viewport.isOpaque = false
        showRules(emptyList())
    }

    fun showRules(rules: List<String>) {
        message.text =
            if (rules.isEmpty())
                "Companion rules will also be generated when needed to preserve literal matching order."
            else "Also generates these lexer rules to preserve matching order:\n" + rules.joinToString("\n")
        message.caretPosition = 0
        isVisible = showEmptyHint || rules.isNotEmpty()
    }
}
