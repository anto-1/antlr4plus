package com.antlr4plus.preview.tree

import com.intellij.ui.components.JBScrollPane
import java.awt.Component
import kotlin.math.max

class ScrollPane(view: Component) : JBScrollPane(view) {
    internal fun updateScrollIncrements(scale: Double) {
        val baseUnitIncrement = 1
        val baseBlockIncrement = 1
        val unitIncrement = max(1, (baseUnitIncrement / scale).toInt())
        val blockIncrement = max(1, (baseBlockIncrement / scale).toInt())
        verticalScrollBar.unitIncrement = unitIncrement
        verticalScrollBar.blockIncrement = blockIncrement
        horizontalScrollBar.unitIncrement = unitIncrement
        horizontalScrollBar.blockIncrement = blockIncrement
    }
}
