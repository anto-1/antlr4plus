package com.antlr4plus.dependencies.ui

import com.antlr4plus.dependencies.UnifiedDependencyResolver
import com.antlr4plus.dependencies.ui.DependencyGraphModel.DependencyNode
import com.antlr4plus.dependencies.ui.DependencyGraphModel.NodeType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Displays dependencies with source navigation and embedded-code previews. */
class DependencyTreePanel(
    private val project: Project,
    graph: UnifiedDependencyResolver.DependencyGraph,
) : JPanel(BorderLayout()) {

    private val tree: Tree
    private val statsLabel: JBLabel
    private val codePreviewPanel: JPanel
    private val codePreviewArea: JTextArea

    private var selectedNode: DependencyNode? = null
    private val previewGeneration = AtomicLong()

    init {
        border = JBUI.Borders.empty(4)

        val projectRoot = project.basePath?.let { File(it) }

        val rootNode = DependencyGraphModel.buildTree(graph, projectRoot)
        val treeRoot = buildTreeNode(rootNode)
        val treeModel = DefaultTreeModel(treeRoot)

        tree = Tree(treeModel)
        tree.cellRenderer = DependencyTreeCellRenderer()
        tree.isRootVisible = true
        tree.showsRootHandles = true

        tree.addTreeWillExpandListener(
            object : javax.swing.event.TreeWillExpandListener {
                override fun treeWillExpand(event: javax.swing.event.TreeExpansionEvent) {}

                override fun treeWillCollapse(event: javax.swing.event.TreeExpansionEvent) {
                    if (event.path.pathCount == 1) {
                        throw javax.swing.tree.ExpandVetoException(event)
                    }
                }
            },
        )

        expandAllNodes(treeRoot)

        tree.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        navigateToSelectedNode()
                    } else if (e.clickCount == 1) {
                        updateCodePreview()
                    }
                }
            },
        )

        val stats = DependencyGraphModel.computeStats(graph)
        statsLabel = JBLabel(buildDependencyStatsHtml(stats))
        statsLabel.border = JBUI.Borders.empty(4, 8)

        val statsPanel = JPanel(BorderLayout())
        statsPanel.add(statsLabel, BorderLayout.CENTER)
        statsPanel.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())

        val treeScroll = JBScrollPane(tree)
        treeScroll.border = JBUI.Borders.empty()
        treeScroll.preferredSize = Dimension(JBUI.scale(350), JBUI.scale(250))

        codePreviewArea = JTextArea()
        codePreviewArea.font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size - 1)
        codePreviewArea.isEditable = false
        codePreviewArea.lineWrap = false
        codePreviewArea.background = UIUtil.getTextFieldBackground()

        val codeScroll = JBScrollPane(codePreviewArea)
        codeScroll.preferredSize = Dimension(JBUI.scale(350), JBUI.scale(100))
        codeScroll.border = BorderFactory.createTitledBorder("Code Preview")

        codePreviewPanel = JPanel(BorderLayout())
        codePreviewPanel.add(codeScroll, BorderLayout.CENTER)
        codePreviewPanel.isVisible = false

        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(statsPanel, BorderLayout.NORTH)
        mainPanel.add(treeScroll, BorderLayout.CENTER)
        mainPanel.add(codePreviewPanel, BorderLayout.SOUTH)

        add(mainPanel, BorderLayout.CENTER)

        val legendPanel = createDependencyLegendPanel()
        add(legendPanel, BorderLayout.SOUTH)
    }

    private fun buildTreeNode(node: DependencyNode): DefaultMutableTreeNode {
        val treeNode = DefaultMutableTreeNode(node)
        for (child in node.children) {
            treeNode.add(buildTreeNode(child))
        }
        return treeNode
    }

    private fun expandAllNodes(node: DefaultMutableTreeNode) {
        tree.expandPath(TreePath(node.path))
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            expandAllNodes(child)
        }
    }

    private fun selectedDependencyNode(): DependencyNode? =
        (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DependencyNode

    private fun navigateToSelectedNode() {
        val node = selectedDependencyNode() ?: return

        val file = node.file ?: return
        val line = node.lineRange?.first?.minus(1)?.coerceAtLeast(0) ?: 0
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val vf = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || !vf.isValid) return@invokeLater
                val descriptor = OpenFileDescriptor(project, vf, line, 0)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            }
        }
    }

    private fun updateCodePreview() {
        val node = selectedDependencyNode() ?: return

        selectedNode = node
        val generation = previewGeneration.incrementAndGet()

        val code = node.codeContent
        if (code != null && code.isNotBlank()) {
            codePreviewArea.text = code.trim()
            codePreviewArea.caretPosition = 0
            codePreviewPanel.isVisible = true
        } else if ((node.type == NodeType.JAVA_FILE || node.type == NodeType.KOTLIN_FILE) && node.file != null) {
            codePreviewArea.text = "Loading..."
            codePreviewPanel.isVisible = true
            val sourceFile = node.file
            ApplicationManager.getApplication().executeOnPooledThread {
                val content = runCatching { sourceFile.readText() }.getOrNull()
                val preview = content?.let {
                    if (it.length > 2000) it.take(2000) + "\n... (truncated)" else it
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || selectedNode !== node || previewGeneration.get() != generation)
                        return@invokeLater
                    if (preview == null) {
                        codePreviewPanel.isVisible = false
                    } else {
                        codePreviewArea.text = preview
                        codePreviewArea.caretPosition = 0
                        codePreviewPanel.isVisible = true
                    }
                }
            }
        } else {
            codePreviewPanel.isVisible = false
        }

        revalidate()
        repaint()
    }

    companion object {
        fun create(
            project: Project,
            graph: UnifiedDependencyResolver.DependencyGraph,
        ): DependencyTreePanel = DependencyTreePanel(project, graph)
    }
}
