package com.antlr4plus.dotG4Support.generators

import com.intellij.ui.CheckboxTreeBase.CheckboxTreeCellRendererBase
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class LiteralChooserRenderer : CheckboxTreeCellRendererBase() {
    override fun customizeRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        if (value is DefaultMutableTreeNode) {
            val userObject = value.getUserObject()
            if (userObject is LiteralChooserObject) {
                userObject.renderTreeNode(textRenderer, tree)
            }
        }
    }
}
