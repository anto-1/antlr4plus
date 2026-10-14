package com.antlr4plus.dependencies.ui

import com.antlr4plus.dependencies.ui.DependencyGraphModel.DependencyNode
import com.antlr4plus.dependencies.ui.DependencyGraphModel.NodeType
import com.antlr4plus.util.escapeHtml
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.UIManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer

internal fun buildDependencyStatsHtml(stats: DependencyGraphModel.GraphStats): String = buildString {
    append("<html><body style='width: 300px;'>")
    append("<b>Dependency Graph Summary</b><br/>")
    append("• ${stats.totalGrammarFiles} grammar file")
    if (stats.totalGrammarFiles != 1) append("s")
    if (stats.grammarsWithCode > 0) {
        append(" (${stats.grammarsWithCode} with embedded code)")
    }
    append("<br/>")

    if (stats.totalEmbeddedBlocks > 0) {
        append("• ${stats.totalEmbeddedBlocks} embedded code block")
        if (stats.totalEmbeddedBlocks != 1) append("s")
        append("<br/>")
    }

    if (stats.sourceFiles > 0) {
        append("• ${stats.sourceFiles} source file")
        if (stats.sourceFiles != 1) append("s")
        append(" (Java/Kotlin)")
        append("<br/>")
    }

    if (stats.unresolvedImports > 0) {
        append("• <span style='color: #FF9800;'>${stats.unresolvedImports} unresolved import")
        if (stats.unresolvedImports != 1) append("s")
        append("</span><br/>")
    }

    append("</body></html>")
}

internal fun createDependencyLegendPanel(): JPanel {
    val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2)))
    panel.border =
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            JBUI.Borders.empty(4),
        )

    panel.add(createLegendItem(AllIcons.Nodes.Method, "Embedded Code", Color(100, 149, 237)))
    panel.add(createLegendItem(AllIcons.General.Warning, "Unresolved", Color(255, 152, 0)))

    return panel
}

private fun createLegendItem(icon: Icon, text: String, color: Color): JPanel {
    val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0))
    panel.isOpaque = false
    panel.add(JLabel(icon))
    val label = JLabel(text)
    label.font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1)
    label.foreground = color
    panel.add(label)
    return panel
}

internal class DependencyTreeCellRenderer : DefaultTreeCellRenderer() {
    companion object {
        private val GRAMMAR_COLOR = Color(199, 125, 187)
        private val UNRESOLVED_COLOR = Color(255, 152, 0)

        private fun colorToHex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)
    }

    init {
        val bg = UIManager.getColor("Panel.background") ?: Color(60, 63, 65)
        backgroundNonSelectionColor = bg
        borderSelectionColor = null
    }

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)

        val treeNode = value as? DefaultMutableTreeNode ?: return this
        val node = treeNode.userObject as? DependencyNode ?: return this

        icon = node.getIcon()

        if (!selected) {
            isOpaque = false
        }

        if (!selected) {
            text = buildColoredText(node)
        } else {
            text = node.getDisplayText()
        }

        foreground = UIUtil.getLabelForeground()

        return this
    }

    private fun buildColoredText(node: DependencyNode): String {
        val mainText = node.relativePath ?: node.name
        val suffix = buildSuffix(node)

        val color: Color? =
            when (node.type) {
                NodeType.GRAMMAR_FILE -> GRAMMAR_COLOR
                NodeType.UNRESOLVED_IMPORT -> UNRESOLVED_COLOR
                else -> null
            }

        return if (color != null && suffix.isNotEmpty()) {
            "<html><span style='color: ${colorToHex(color)}'>${escapeHtml(mainText)}</span>${escapeHtml(suffix)}</html>"
        } else if (color != null) {
            "<html><span style='color: ${colorToHex(color)}'>${escapeHtml(mainText)}</span></html>"
        } else {
            mainText + suffix
        }
    }

    private fun buildSuffix(node: DependencyNode): String = buildString {
        if (
            node.details != null &&
                node.type != NodeType.GRAMMAR_FILE &&
                node.type != NodeType.JAVA_FILE &&
                node.type != NodeType.KOTLIN_FILE
        ) {
            append(" (${node.details})")
        }
        if (
            node.reachability != null &&
                node.reachability != "main" &&
                node.type != NodeType.EMBEDDED_CODE &&
                node.type != NodeType.UNRESOLVED_IMPORT
        ) {
            append(" ← ${node.reachability}")
        }
    }
}
