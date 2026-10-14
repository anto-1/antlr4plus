package com.antlr4plus.editor

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.sin

object EditorAutoScroll {
    fun scrollEditorWithSelection(editor: Editor, startIdx: Int, stopIdx: Int) {
        runReadAction {
            val document = editor.document
            if (startIdx < 0 || stopIdx + 1 > document.textLength) return@runReadAction
            val selectionModel = editor.selectionModel
            selectionModel.removeSelection()
            selectionModel.setSelection(startIdx, stopIdx + 1)

            val startLine = document.getLineNumber(startIdx)
            val startLineOffset = document.getLineStartOffset(startLine)
            val logicalPosition = editor.offsetToLogicalPosition(startLineOffset)

            val visualPosition = editor.logicalToVisualPosition(logicalPosition)
            val point = editor.visualPositionToXY(visualPosition)

            val viewport = editor.scrollingModel.visibleArea
            val viewportHeight = viewport.height

            val targetY = (point.y - viewportHeight / 2).coerceAtLeast(0)
            editor.scrollingModel.scrollVertically(targetY)
        }
    }

    /** Starts a pulsing range highlight and returns its cleanup action. Start and stop on the EDT. */
    fun breathingRange(editor: Editor, start: Int, end: Int): Runnable {
        val contentComponent = editor.contentComponent
        val startPos = editor.offsetToXY(start)
        val endPos = editor.offsetToXY(end)
        val lineHeight = editor.lineHeight
        val startLine = editor.document.getLineNumber(start)
        val endLine = editor.document.getLineNumber(end.coerceAtMost(editor.document.textLength - 1))

        val isLight = (UIUtil.getPanelBackground().let { (it.red + it.green + it.blue) / 3 }) > 128
        val glowColor = Color.WHITE

        val overlay =
            object : JComponent() {
                var alpha = 0.0f

                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON,
                    )
                    g2.composite = AlphaComposite.SrcOver.derive(alpha)
                    g2.color = glowColor

                    if (startLine == endLine) {
                        val w = (endPos.x - startPos.x).coerceAtLeast(4)
                        g2.fillRoundRect(startPos.x, startPos.y, w, lineHeight, 6, 6)
                    } else {
                        val editorWidth = contentComponent.width
                        for (line in startLine..endLine) {
                            val lineStart = editor.document.getLineStartOffset(line)
                            val lx = if (line == startLine) startPos.x else 0
                            val ly = editor.offsetToXY(lineStart).y
                            val lw = if (line == endLine) endPos.x - lx else editorWidth - lx
                            g2.fillRoundRect(lx, ly, lw.coerceAtLeast(4), lineHeight, 6, 6)
                        }
                    }
                }
            }
        overlay.isOpaque = false
        overlay.setBounds(0, 0, contentComponent.width, contentComponent.height)
        contentComponent.add(overlay)
        contentComponent.setComponentZOrder(overlay, 0)
        contentComponent.repaint()

        val maxAlpha = if (isLight) 0.45f else 0.30f
        val minAlpha = if (isLight) 0.10f else 0.05f
        var phase = 0.0
        val timer =
            Timer(40) {
                phase += 0.32
                val sin = (sin(phase) + 1.0) / 2.0
                overlay.alpha = minAlpha + (maxAlpha - minAlpha) * sin.toFloat()
                overlay.repaint()
            }
        timer.isRepeats = true
        timer.start()

        return Runnable {
            timer.stop()
            contentComponent.remove(overlay)
            contentComponent.repaint()
        }
    }
}
