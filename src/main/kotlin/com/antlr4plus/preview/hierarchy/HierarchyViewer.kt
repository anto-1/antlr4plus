package com.antlr4plus.preview.hierarchy

import com.antlr4plus.animation.AnimationUtils
import com.antlr4plus.color.brighten
import com.antlr4plus.color.buttonColor
import com.antlr4plus.icons.Icons
import com.antlr4plus.listener.ParsingResultSelectionListener
import com.antlr4plus.notifications.NotificationsUtils
import com.antlr4plus.preview.CenteredSelectionScrollPolicy
import com.antlr4plus.preview.PreviewButtonInteraction
import com.antlr4plus.preview.containsCircularPreviewPoint
import com.antlr4plus.preview.findHierarchyNodeAtOffset
import com.antlr4plus.preview.formatHierarchyText
import com.antlr4plus.preview.getHierarchySelectionOffset
import com.antlr4plus.preview.paintCopyTreeButton
import com.antlr4plus.preview.tree.DefaultTreeTextProvider
import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.awt.geom.Ellipse2D
import java.lang.Runnable
import java.lang.System
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import javax.swing.*
import javax.swing.event.TreeSelectionEvent
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.*
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import org.antlr.v4.gui.TreeTextProvider
import org.antlr.v4.runtime.tree.ParseTree

class HierarchyViewer(
    private val project: Project,
    tree: org.antlr.v4.runtime.tree.Tree?,
    private val parentDisposable: Disposable = project,
) : JPanel(), TreeSelectionListener {

    private sealed class DisplayState {
        object Loading : DisplayState()

        object Empty : DisplayState()

        object HasTree : DisplayState()
    }

    val myTree: JTree = com.intellij.ui.treeStructure.Tree()
    private val selectionListeners: MutableList<ParsingResultSelectionListener> = ArrayList()
    private var treeTextProvider: TreeTextProvider? = null
    private var activeAnimation: AnimationUtils.Animation? = null

    private val emptyStateLabel =
        JLabel("Nothing to Show").apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = Color.GRAY
        }
    private lateinit var scrollPane: JScrollPane

    private var displayState: DisplayState = DisplayState.Loading
    private var hasTreeData = false

    lateinit var cursorFollowFab: CursorFollowFab

    lateinit var copyTreeFab: CopyTreeFab

    /** Expand the hierarchy to follow the caret in the .g4p editor. */
    var cursorFollowEnabled = false
        private set

    fun resetCursorFollowMode() {
        cursorFollowEnabled = false
        if (::cursorFollowFab.isInitialized) {
            cursorFollowFab.isActive = false
        }
    }

    /** Exports a text tree with terminal labels enclosed in angle brackets. */
    fun generateTreeText(): String =
        formatHierarchyText(
            myTree.model?.root as? DefaultMutableTreeNode?,
        ) {
            treeTextProvider?.getText(it)
        }

    fun copyTreeToClipboard() {
        val treeText = generateTreeText()
        if (treeText.isNotEmpty()) {
            val selection = StringSelection(treeText)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            NotificationsUtils.showToast(project, "Hierarchy copied to clipboard")
        }
    }

    private var expansionDebounceJob: Job? = null
    private val treeGeneration = AtomicLong()
    private val navigationGeneration = AtomicLong()
    private var lastUpdateTime = System.nanoTime()
    private var ignoreValueChangeFlag: Boolean = false

    init {
        setupComponents()
        setupTree(tree)
        setupMessageBusSubscriptions()
    }

    private fun setupMessageBusSubscriptions() {
        val connection = project.messageBus.connect(parentDisposable)

        connection.subscribe(
            PreviewService.STATUS_UPDATE_TOPIC,
            object : PreviewService.StatusUpdateListener {
                override fun onStatusUpdate(message: String, isLoading: Boolean) {
                    if (isLoading && message.isNotEmpty()) {
                        displayState = DisplayState.Loading
                        updateDisplayCard()
                    } else if (!isLoading && !hasTreeData) {
                        displayState = DisplayState.Empty
                        updateDisplayCard()
                    }
                }
            },
        )

        connection.subscribe(
            PreviewService.PLUGIN_TOGGLE_TOPIC,
            object : PreviewService.PluginToggleListener {
                override fun onPluginToggled(enabled: Boolean) {
                    if (enabled) {
                        displayState = DisplayState.Loading
                        updateDisplayCard()
                    }
                }
            },
        )

        connection.subscribe(
            PreviewService.JOB_CANCELLED_TOPIC,
            object : PreviewService.JobCancelledListener {
                override fun onJobCancelled() {
                    // Keep the loading card behind the retry overlay.
                    displayState = DisplayState.Loading
                    updateDisplayCard()
                }
            },
        )
    }

    private fun updateDisplayCard() {
        val cardLayout = layout as CardLayout
        when (displayState) {
            DisplayState.Loading -> {
                // Keep the current card while the loading overlay covers it.
            }
            DisplayState.Empty -> {
                cardLayout.show(this@HierarchyViewer, "empty")
            }
            DisplayState.HasTree -> {
                cardLayout.show(this@HierarchyViewer, "tree")
            }
        }
    }

    fun resetData() {
        stopActiveAnimation()
        myTree.clearSelection()
        setTree(null)
        setTreeTextProvider(null)
        myTree.revalidate()
        myTree.repaint()
    }

    fun addParsingResultSelectionListener(listener: ParsingResultSelectionListener?) {
        if (listener != null) {
            selectionListeners.add(listener)
        }
    }

    fun removeParsingResultSelectionListener(listener: ParsingResultSelectionListener?) {
        if (listener != null) {
            selectionListeners.remove(listener)
        }
    }

    private fun setupComponents() {
        layout = CardLayout()
        scrollPane = JBScrollPane(myTree)
        scrollPane.border = null

        cursorFollowFab = CursorFollowFab()
        cursorFollowFab.isActive = cursorFollowEnabled
        cursorFollowFab.toolTipText = "Toggle caret follow mode"
        cursorFollowFab.onClickAction = {
            cursorFollowEnabled = !cursorFollowEnabled
            cursorFollowFab.isActive = cursorFollowEnabled
        }

        copyTreeFab = CopyTreeFab()
        copyTreeFab.toolTipText = "Copy hierarchy to clipboard"
        copyTreeFab.onClickAction = {
            copyTreeToClipboard()
        }

        val containerPanel =
            object : JPanel(null) {
                // Overlapping buttons require Swing to repaint intersecting children.
                override fun isOptimizedDrawingEnabled(): Boolean = false

                override fun doLayout() {
                    scrollPane.setBounds(0, 0, width, height)

                    val fabSize = cursorFollowFab.preferredSize
                    val margin = 12
                    val spacing = 8

                    cursorFollowFab.setBounds(
                        width - fabSize.width - margin,
                        height - fabSize.height - margin,
                        fabSize.width,
                        fabSize.height,
                    )

                    copyTreeFab.setBounds(
                        width - fabSize.width - margin - spacing - fabSize.width,
                        height - fabSize.height - margin,
                        fabSize.width,
                        fabSize.height,
                    )
                }
            }

        containerPanel.add(scrollPane)
        containerPanel.add(copyTreeFab)
        containerPanel.add(cursorFollowFab)
        containerPanel.setComponentZOrder(copyTreeFab, 0)
        containerPanel.setComponentZOrder(cursorFollowFab, 1)
        containerPanel.setComponentZOrder(scrollPane, 2)

        add(containerPanel, "tree")
        add(emptyStateLabel, "empty")

        (layout as CardLayout).show(this, "tree")

        scrollPane.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    stopActiveAnimation()
                }

                override fun mouseClicked(e: MouseEvent?) {
                    stopActiveAnimation()
                }
            },
        )

        scrollPane.addMouseWheelListener { e: MouseWheelEvent ->
            if (e.wheelRotation != 0) {
                stopActiveAnimation()
            }
        }

        scrollPane.verticalScrollBar.addAdjustmentListener { e: AdjustmentEvent ->
            if (e.valueIsAdjusting) {
                stopActiveAnimation()
            }
        }
        scrollPane.horizontalScrollBar.addAdjustmentListener { e: AdjustmentEvent ->
            if (e.valueIsAdjusting) {
                stopActiveAnimation()
            }
        }
    }

    private fun setupTree(tree: org.antlr.v4.runtime.tree.Tree?) {
        setTree(tree)
        updateTreeRenderer()
        myTree.addTreeSelectionListener(this)
        myTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        myTree.isFocusable = true
        myTree.isRequestFocusEnabled = true
    }

    private fun updateTreeRenderer() {
        myTree.cellRenderer =
            object : DefaultTreeCellRenderer() {
                init {
                    val bg = UIManager.getColor("Panel.background") ?: Color(60, 63, 65)
                    backgroundNonSelectionColor = bg
                    borderSelectionColor = null
                }

                override fun getTreeCellRendererComponent(
                    tree: JTree?,
                    value: Any?,
                    sel: Boolean,
                    expanded: Boolean,
                    leaf: Boolean,
                    row: Int,
                    hasFocus: Boolean,
                ): Component {
                    super.getTreeCellRendererComponent(
                        tree,
                        value,
                        sel,
                        expanded,
                        leaf,
                        row,
                        hasFocus,
                    )
                    val node = value as DefaultMutableTreeNode
                    val userObject = node.userObject

                    treeTextProvider?.let {
                        text =
                            if (userObject is org.antlr.v4.runtime.tree.Tree) {
                                it.getText(userObject)
                            } else {
                                userObject?.toString() ?: "null"
                            }
                    }

                    if (!sel) {
                        isOpaque = false
                    }

                    icon = if (leaf) Icons.LEXER_RULE else Icons.PARSER_RULE
                    return this
                }
            }
    }

    private fun debounceExpandPath(
        path: TreePath?,
        expectedTreeGeneration: Long,
        expectedNavigationGeneration: Long,
        onExpand: Runnable,
    ) {
        if (path == null) {
            SwingUtilities.invokeLater {
                if (
                    !project.isDisposed &&
                        treeGeneration.get() == expectedTreeGeneration &&
                        navigationGeneration.get() == expectedNavigationGeneration
                ) {
                    onExpand.run()
                }
            }
            return
        }
        expansionDebounceJob?.cancel()
        expansionDebounceJob =
            project.service<PreviewService>().cs.launch {
                delay(EXPANSION_DEBOUNCE_DELAY_MS.milliseconds)
                SwingUtilities.invokeLater {
                    if (
                        project.isDisposed ||
                            treeGeneration.get() != expectedTreeGeneration ||
                            navigationGeneration.get() != expectedNavigationGeneration
                    )
                        return@invokeLater
                    myTree.expandPath(path)
                    myTree.revalidate()
                    myTree.repaint()
                    onExpand.run()
                }
            }
    }

    fun stopActiveAnimation() {
        expansionDebounceJob?.cancel()
        expansionDebounceJob = null
        navigationGeneration.incrementAndGet()
        activeAnimation?.let {
            it.stop()
            for (listener in it.timer.actionListeners) {
                it.timer.removeActionListener(listener)
            }
            activeAnimation = null
        }
    }

    private fun computeTargetViewPosition(path: TreePath, viewport: JViewport): Point {
        var bounds = myTree.getPathBounds(path)
        val viewportSize = viewport.extentSize
        val contentSize = myTree.size

        if (bounds == null) {
            val rowHeight = if (myTree.rowHeight > 0) myTree.rowHeight else 20
            val row = myTree.getRowForPath(path)
            val estimatedY = if (row >= 0) row * rowHeight else path.pathCount * rowHeight
            val estimatedX = path.pathCount * 20
            bounds = Rectangle(estimatedX, estimatedY, 100, rowHeight)
        }

        var targetX = bounds.x + bounds.width / 2 - viewportSize.width / 2
        val targetY =
            CenteredSelectionScrollPolicy.centeredViewportStart(
                itemStart = bounds.y,
                itemSize = bounds.height,
                viewportSize = viewportSize.height,
                contentSize = contentSize.height,
            )

        targetX = max(0, min(targetX, max(0, contentSize.width - viewportSize.width)))
        return Point(targetX, targetY)
    }

    fun centerTargetNode(path: TreePath) {
        assert(SwingUtilities.isEventDispatchThread()) { "centerTargetNode must be called on EDT" }
        val currentTime = System.nanoTime()
        val isHighFrequency = CenteredSelectionScrollPolicy.isHighFrequency(lastUpdateTime, currentTime)
        lastUpdateTime = currentTime

        val viewport = myTree.parent as JViewport?
        if (viewport == null) {
            ignoreValueChangeFlag = true
            myTree.selectionPath = path
            ignoreValueChangeFlag = false
            return
        }

        stopActiveAnimation()
        val expectedTreeGeneration = treeGeneration.get()
        val expectedNavigationGeneration = navigationGeneration.get()
        myTree.revalidate()
        myTree.repaint()

        val computeTargetPosition = Supplier { computeTargetViewPosition(path, viewport) }

        val finalPath: TreePath = path

        if (isHighFrequency) {
            myTree.expandPath(path.parentPath)
            myTree.revalidate()
            val targetPos: Point = computeTargetPosition.get()
            viewport.setViewPosition(targetPos)
            ignoreValueChangeFlag = true
            myTree.selectionPath = finalPath
            ignoreValueChangeFlag = false
            viewport.repaint()
        } else {
            debounceExpandPath(
                path.parentPath,
                expectedTreeGeneration,
                expectedNavigationGeneration,
            ) onExpand@{
                if (
                    treeGeneration.get() != expectedTreeGeneration ||
                        navigationGeneration.get() != expectedNavigationGeneration
                )
                    return@onExpand
                myTree.revalidate()
                myTree.repaint()
                SwingUtilities.invokeLater animate@{
                    if (
                        project.isDisposed ||
                            treeGeneration.get() != expectedTreeGeneration ||
                            navigationGeneration.get() != expectedNavigationGeneration
                    )
                        return@animate
                    val targetPos = computeTargetPosition.get()
                    val postExpansionPos = viewport.viewPosition
                    activeAnimation =
                        AnimationUtils.createDynamicAnimation(
                            { x: Int, y: Int ->
                                viewport.setViewPosition(Point(x, y))
                                viewport.repaint()
                            },
                            postExpansionPos.x,
                            postExpansionPos.y,
                            targetPos.x,
                            targetPos.y,
                            computeTargetPosition,
                        )

                    activeAnimation?.let { animation ->
                        animation.timer.addActionListener { _: ActionEvent? ->
                            if (
                                treeGeneration.get() != expectedTreeGeneration ||
                                    navigationGeneration.get() != expectedNavigationGeneration
                            ) {
                                animation.stop()
                                if (activeAnimation === animation) activeAnimation = null
                            } else if (activeAnimation === animation && !animation.timer.isRunning) {
                                ignoreValueChangeFlag = true
                                myTree.selectionPath = finalPath
                                ignoreValueChangeFlag = false
                                viewport.repaint()
                                activeAnimation = null
                            }
                        }
                        animation.start()
                    }
                }
            }
        }
    }

    fun setTree(tree: org.antlr.v4.runtime.tree.Tree?) {
        val requestGeneration = treeGeneration.incrementAndGet()
        val install = Runnable {
            if (project.isDisposed || treeGeneration.get() != requestGeneration) return@Runnable
            installTreeModel(wrapHierarchyTree(tree), tree != null, requestGeneration)
        }
        if (SwingUtilities.isEventDispatchThread()) install.run() else SwingUtilities.invokeLater(install)
    }

    /** Wraps the tree synchronously; call off the EDT, then install it with [setTreeWithModel]. */
    fun wrapTree(tree: org.antlr.v4.runtime.tree.Tree?): DefaultMutableTreeNode? = wrapHierarchyTree(tree)

    /** Installs a precomputed model, invalidating every delayed update for the previous tree. */
    fun setTreeWithModel(wrappedRoot: DefaultMutableTreeNode?, hasTree: Boolean) {
        val requestGeneration = treeGeneration.incrementAndGet()
        val install = Runnable {
            if (project.isDisposed || treeGeneration.get() != requestGeneration) return@Runnable
            installTreeModel(wrappedRoot, hasTree, requestGeneration)
        }
        if (SwingUtilities.isEventDispatchThread()) install.run() else SwingUtilities.invokeLater(install)
    }

    private fun installTreeModel(
        wrappedRoot: DefaultMutableTreeNode?,
        hasTree: Boolean,
        expectedGeneration: Long,
    ) {
        if (treeGeneration.get() != expectedGeneration) return
        stopActiveAnimation()
        myTree.clearSelection()
        myTree.model = DefaultTreeModel(wrappedRoot, false)
        updateTreeRenderer()
        hasTreeData = hasTree
        if (hasTreeData) {
            displayState = DisplayState.HasTree
        } else if (displayState != DisplayState.Loading) {
            displayState = DisplayState.Empty
        }
        updateDisplayCard()
    }

    /** Supply the matching Java or Kotlin vocabulary to resolve token names. */
    fun setRuleNamesWithVocabulary(
        ruleNames: List<String>,
        vocabulary: org.antlr.v4.runtime.Vocabulary? = null,
        kotlinVocabulary: Any? = null,
    ) {
        treeTextProvider = DefaultTreeTextProvider(ruleNames, vocabulary, kotlinVocabulary)
        updateTreeRenderer()
    }

    fun setTreeTextProvider(provider: TreeTextProvider?) {
        treeTextProvider = provider
        updateTreeRenderer()
    }

    val selectionPath: TreePath?
        get() = myTree.selectionPath

    fun selectNodeAtOffset(editorOffset: Int) {
        val root = myTree.model?.root as? DefaultMutableTreeNode? ?: return
        val tree = root.userObject as org.antlr.v4.runtime.tree.Tree
        if (tree is ParseTree) {
            val atOffset = getNodeAtOffset(root, editorOffset)
            if (atOffset != null) {
                val path = TreePath(atOffset.path)
                ignoreValueChangeFlag = true
                myTree.selectionModel.selectionPath = path
                ignoreValueChangeFlag = false
                val isRootNode = atOffset === root
                if (isRootNode) {
                    return
                }
                centerTargetNode(path)
            }
        }
    }

    fun getNodeAtOffset(node: DefaultMutableTreeNode, offset: Int): DefaultMutableTreeNode? =
        findHierarchyNodeAtOffset(node, offset)

    override fun valueChanged(e: TreeSelectionEvent) {
        if (ignoreValueChangeFlag) {
            ignoreValueChangeFlag = false
            return
        }
        val path = e.newLeadSelectionPath ?: return
        val node = path.lastPathComponent as DefaultMutableTreeNode
        val tree = node.userObject as org.antlr.v4.runtime.tree.Tree

        for (listener in selectionListeners) {
            listener.onParserRuleSelected(tree)
        }

        val offset = getHierarchySelectionOffset(path)
        if (offset >= 0) {
            centerTargetNode(path)
        }
    }

    companion object {
        private const val EXPANSION_DEBOUNCE_DELAY_MS: Long = 200
    }

    class CursorFollowFab : JButton() {
        private val fabSize = 34

        private val interaction = PreviewButtonInteraction(this)

        var isActive = false
            set(value) {
                field = value
                repaint()
            }

        var onClickAction: (() -> Unit)? = null

        init {
            interaction.install(fabSize, fabSize) { onClickAction?.invoke() }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            g2.color = Color(0, 0, 0, 40)
            g2.fill(Ellipse2D.Double(2.0, 3.0, (width - 4).toDouble(), (height - 4).toDouble()))

            val baseColor =
                if (isActive) {
                    UIManager.getColor("List.selectionBackground") ?: Color(47, 101, 202)
                } else {
                    buttonColor(0.02)
                }
            val stateBrightness =
                when {
                    interaction.isPressed -> 0.08
                    interaction.isHovered -> 0.04
                    else -> 0.0
                }
            g2.color = if (stateBrightness > 0) baseColor.brighten(stateBrightness) else baseColor
            g2.fill(Ellipse2D.Double(0.0, 0.0, (width - 4).toDouble(), (height - 4).toDouble()))

            val icon = Icons.LOCATE
            val scale = 1.25
            val scaledWidth = (icon.iconWidth * scale).toInt()
            val scaledHeight = (icon.iconHeight * scale).toInt()
            val iconX = (width - 4 - scaledWidth) / 2
            val iconY = (height - 4 - scaledHeight) / 2

            val originalTransform = g2.transform
            g2.translate(iconX, iconY)
            g2.scale(scale, scale)
            icon.paintIcon(this, g2, 0, 0)
            g2.transform = originalTransform

            g2.dispose()
        }
    }

    class CopyTreeFab : JButton() {
        private val fabSize = 34

        private val interaction = PreviewButtonInteraction(this)

        var onClickAction: (() -> Unit)? = null

        init {
            interaction.install(fabSize, fabSize) { onClickAction?.invoke() }
        }

        override fun paintComponent(g: Graphics): Unit = paintCopyTreeButton(g, interaction)

        override fun contains(x: Int, y: Int): Boolean = containsCircularPreviewPoint(x, y)
    }
}
