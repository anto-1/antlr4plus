package com.antlr4plus.dotG4Support.generators

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import javax.swing.Icon
import javax.swing.JTree

class LiteralChooserObject(@JvmField val text: String, private val icon: Icon?) {
    fun renderTreeNode(component: SimpleColoredComponent, tree: JTree) {
        val literal = this.text
        val attributes = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, tree.getForeground())
        component.append(literal, attributes)
        component.setIcon(icon)
    }
}
