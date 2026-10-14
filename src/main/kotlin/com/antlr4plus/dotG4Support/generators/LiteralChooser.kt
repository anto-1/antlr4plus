package com.antlr4plus.dotG4Support.generators

import com.antlr4plus.icons.Icons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.TreeSelectionEvent
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultTreeModel

class LiteralChooser(
    project: Project?,
    literals: MutableList<String>,
    private val companionRules: (List<String>) -> List<String> = { emptyList() },
) : DialogWrapper(project, true) {
    var tree: Tree
    var selectedElements: java.util.LinkedHashSet<String?> = java.util.LinkedHashSet<String?>()
    private val companionNotice = LexerRuleNotice(showEmptyHint = true)

    init {
        tree = createTree(literals)
        title = "Choose Literal Rules to Generate"
        init()
    }

    fun createTree(literals: List<String>): Tree {
        val rootNode = CheckedTreeNode("all literals not defined")
        literals.forEach { literal ->
            val child = CheckedTreeNode(LiteralChooserObject(literal, Icons.LEXER_RULE))
            child.isChecked = true
            rootNode.add(child)
        }
        val treeModel = DefaultTreeModel(rootNode)

        selectedElements.addAll(literals)

        val tree = Tree(treeModel)
        tree.isRootVisible = false
        tree.cellRenderer = LiteralChooserRenderer()
        tree.addTreeSelectionListener(MyTreeSelectionListener())

        return tree
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        object : DoubleClickListener() {
                override fun onDoubleClick(e: MouseEvent): Boolean {
                    if (tree.getPathForLocation(e.getX(), e.getY()) != null) {
                        doOKAction()
                        return true
                    }
                    return false
                }
            }
            .installOn(tree)

        TreeUtil.installActions(tree)
        val scrollPane = ScrollPaneFactory.createScrollPane(tree)
        panel.add(scrollPane, BorderLayout.CENTER)
        companionNotice.showRules(companionRules(selectedElements.filterNotNull()))
        panel.add(companionNotice, BorderLayout.SOUTH)

        return panel
    }

    private inner class MyTreeSelectionListener : TreeSelectionListener {
        override fun valueChanged(e: TreeSelectionEvent) {
            val paths = e.getPaths() ?: return
            for (i in paths.indices) {
                val node = paths[i].lastPathComponent
                if (node is CheckedTreeNode) {
                    val userObject = node.getUserObject()
                    if (userObject is LiteralChooserObject) {
                        val text = userObject.text
                        if (e.isAddedPath(paths[i])) {
                            if (selectedElements.contains(text)) {
                                selectedElements.remove(text)
                            } else {
                                selectedElements.add(text)
                            }

                            node.setChecked(!node.isChecked())
                        }
                    }
                }
            }
            companionNotice.showRules(companionRules(selectedElements.filterNotNull()))
            tree.repaint()
        }
    }

    fun getSelectedElements(): MutableList<String?>? =
        (if (exitCode == OK_EXIT_CODE) selectedElements else null)?.let { ArrayList(it) }
}
