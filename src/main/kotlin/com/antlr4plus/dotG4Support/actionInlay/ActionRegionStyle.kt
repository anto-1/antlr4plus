package com.antlr4plus.dotG4Support.actionInlay

import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color

internal fun createActionRegionAttributes(): TextAttributes {
    val fill =
        JBColor(
            Color(0x5A, 0x5A, 0x5A, 0x20),
            Color(0x5A, 0x5A, 0x5A, 0x2A),
        )

    return TextAttributes().apply {
        effectColor = null
        effectType = null
        backgroundColor = fill
    }
}
