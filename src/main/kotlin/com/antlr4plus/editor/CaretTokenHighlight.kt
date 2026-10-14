package com.antlr4plus.editor

import com.antlr4plus.timeline.TokenWithMode
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** Removes this key's caret-token markup. Call on the EDT. */
internal fun clearCaretTokenHighlight(editor: Editor, key: Key<Boolean>) =
    editor.markupModel.allHighlighters
        .filter { it.getUserData(key) == true }
        .forEach { editor.markupModel.removeHighlighter(it) }

/** Updates caret-following token markup. Call on the EDT. */
internal fun updateCaretTokenHighlight(
    editor: Editor,
    tokens: List<TokenWithMode>,
    offset: Int,
    showHighlight: Boolean,
    showGutter: Boolean,
    key: Key<Boolean>,
) {
    clearCaretTokenHighlight(editor, key)

    // Between tokens or at end of input, use the nearest preceding token.
    val token =
        tokens.firstOrNull { offset >= it.startIndex && offset <= it.stopIndex }
            ?: tokens.lastOrNull { it.startIndex <= offset }
            ?: return
    if (token.startIndex < 0 || token.stopIndex + 1 > editor.document.textLength) return

    val panelBg = UIUtil.getPanelBackground()
    val isLight = (panelBg.red + panelBg.green + panelBg.blue) / 3 > 128

    // Empty text attributes still carry the gutter indicator when highlighting is hidden.
    val attrs =
        if (showHighlight) {
            TextAttributes().apply {
                backgroundColor = if (isLight) Color(200, 220, 255, 80) else Color(80, 120, 200, 60)
                effectType = EffectType.ROUNDED_BOX
                effectColor = if (isLight) Color(100, 150, 255, 120) else Color(80, 140, 220, 100)
            }
        } else {
            TextAttributes()
        }
    val h =
        editor.markupModel.addRangeHighlighter(
            token.startIndex,
            token.stopIndex + 1,
            HighlighterLayer.SELECTION - 1,
            attrs,
            HighlighterTargetArea.EXACT_RANGE,
        )
    h.putUserData(key, true)

    if (!showGutter) return
    val gutterColor = if (isLight) Color(100, 150, 255) else Color(80, 140, 220)
    h.gutterIconRenderer =
        object : GutterIconRenderer() {
            private val icon =
                object : Icon {
                    override fun getIconWidth() = 4

                    override fun getIconHeight() = editor.lineHeight

                    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                        val g2 = g as Graphics2D
                        g2.setRenderingHint(
                            RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON,
                        )
                        g2.color = gutterColor
                        g2.fillRoundRect(x, y + 1, 4, iconHeight - 2, 3, 3)
                    }
                }

            override fun getIcon() = icon

            override fun equals(other: Any?) = other is GutterIconRenderer && other.icon.iconWidth == 4

            override fun hashCode() = 31

            override fun getAlignment() = Alignment.LEFT
        }
}
