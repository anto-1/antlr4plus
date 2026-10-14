package com.antlr4plus.preview.tree

import com.antlr4plus.preview.ParseTreeContextualMenu
import com.antlr4plus.preview.tree.HardcodedConfigs.OFFSET_X_DP
import com.antlr4plus.preview.tree.HardcodedConfigs.OFFSET_Y_DP
import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.awt.Cursor
import java.awt.Point
import java.awt.event.AdjustmentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.geom.Point2D
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.min
import org.antlr.v4.runtime.tree.Tree

class InputHandler(
    private val project: Project,
    val treeViewer: TreeViewer,
    val viewer: UberTreeViewer,
) {
    private val selectionListeners = mutableListOf<TreeSelectionListener>()
    private var isDragging: Boolean = false
    private var holdPointOnView: Point? = null
    private var lastDragEventUpdateTime: Long = 0
    private val dragEventIntervalMs = 16.67

    // Allow time to move from a node to its clickable parent tooltip.
    private var tooltipHideTimer: Timer? = null
    private val tooltipHideDelayMs = 1000

    init {
        initKeyListener()
        initMouseListener()
        initDragListener()
        initializeTreeNodeSelectionListener()
        initScrollpaneWheelListener()
        initScrollPaneMouseListener()
        initScrollBarAdjustmentListener(viewer.scrollpane.verticalScrollBar)
        initScrollBarAdjustmentListener(viewer.scrollpane.horizontalScrollBar)
        initScalesliderChangeListener()
        initTreeViewerHoverAndExitListeners(viewer.treeViewer)
    }

    private fun cancelTooltipHideTimer() {
        tooltipHideTimer?.stop()
        tooltipHideTimer = null
    }

    private fun startTooltipHideTimer() {
        if (tooltipHideTimer?.isRunning == true) return

        tooltipHideTimer =
            Timer(tooltipHideDelayMs) {
                    treeViewer.hoveredNode = null
                    treeViewer.repaint()
                    tooltipHideTimer = null
                }
                .apply {
                    isRepeats = false
                    start()
                }
    }

    private fun initTreeViewerHoverAndExitListeners(treeViewer: TreeViewer) {
        treeViewer.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    if (treeViewer.treeLayout != null) {
                        val node = treeViewer.getNodeAt(e.x, e.y)
                        if (node != null) {
                            cancelTooltipHideTimer()
                            treeViewer.hoveredNode = node
                        } else {
                            val modelX = e.x / treeViewer.scale - treeViewer.viewportPadding
                            val modelY = e.y / treeViewer.scale - treeViewer.viewportPadding
                            val tooltipInfo = treeViewer.getTooltipInfo(treeViewer.hoveredNode)
                            if (tooltipInfo != null) {
                                // Keep the tooltip open while crossing the gap above the node.
                                val nodeBounds = treeViewer.hoveredNode?.let { treeViewer.getBoundsOfNode(it) }
                                val inTooltip = tooltipInfo.bounds.contains(modelX, modelY)
                                val inGapBetweenTooltipAndNode =
                                    nodeBounds != null &&
                                        modelX >= tooltipInfo.bounds.minX &&
                                        modelX <= tooltipInfo.bounds.maxX &&
                                        modelY >= tooltipInfo.bounds.maxY &&
                                        modelY <= nodeBounds.minY
                                if (inTooltip || inGapBetweenTooltipAndNode) {
                                    cancelTooltipHideTimer()
                                } else {
                                    startTooltipHideTimer()
                                }
                            } else {
                                startTooltipHideTimer()
                            }
                        }
                        treeViewer.repaint()
                    }
                }
            },
        )
        viewer.treeViewer.addMouseListener(
            object : MouseAdapter() {
                override fun mouseExited(e: MouseEvent) {
                    startTooltipHideTimer()
                }
            },
        )
    }

    var temporaryReferenceNode: Tree? = null

    private fun initScalesliderChangeListener() {
        viewer.scaleslider.addChangeListener {
            viewer.scrollpane.updateScrollIncrements(viewer.treeViewer.scale)
            val scrollPane =
                SwingUtilities.getAncestorOfClass(JScrollPane::class.java, viewer.treeViewer) as JScrollPane?
            scrollPane?.let {
                val viewport = it.viewport
                val newScale =
                    (1.0 + viewer.scaleslider.value / 1000.0).coerceIn(
                        HardcodedConfigs.MIN_SCALE,
                        HardcodedConfigs.MAX_SCALE,
                    )
                val isAdjusting = viewer.scaleslider.valueIsAdjusting

                if (isAdjusting) {
                    if (viewer.selectedNode == null && temporaryReferenceNode == null) {
                        temporaryReferenceNode = viewer.treeViewer.findCentermostNode(viewport)
                    }
                } else {
                    temporaryReferenceNode = null
                }

                val oldScale = viewer.treeViewer.scale
                val oldViewPosition = viewport.viewPosition
                val viewportWidth = viewport.width
                val viewportHeight = viewport.height
                val currentViewX = oldViewPosition.x + viewportWidth / 2.0
                val currentViewY = oldViewPosition.y + viewportHeight / 2.0
                val currentModelX = currentViewX / oldScale
                val currentModelY = currentViewY / oldScale

                viewer.treeViewer.scale = newScale

                val nodeToFocus = viewer.selectedNode ?: temporaryReferenceNode
                val centerPoint: Point2D
                val offsetX: Int
                val offsetY: Int

                if (nodeToFocus != null) {
                    val bounds = viewer.treeViewer.getBoundsOfNode(nodeToFocus) ?: return@addChangeListener
                    centerPoint =
                        Point2D.Double(
                            bounds.x + bounds.width / 2.0,
                            bounds.y + bounds.height / 2.0,
                        )
                    offsetX = Utilities.dpToPixels(OFFSET_X_DP)
                    offsetY = Utilities.dpToPixels(OFFSET_Y_DP)
                } else {
                    centerPoint = Point2D.Double(currentModelX, currentModelY)
                    offsetX = 0
                    offsetY = 0
                }

                viewer.animationManager.adjustScrollForZoom(
                    centerPoint,
                    it,
                    newScale,
                    offsetX,
                    offsetY,
                )
                viewer.treeViewer.validate()
                viewport.repaint()
            }
        }
    }

    private fun initScrollPaneMouseListener() {
        viewer.scrollpane.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    viewer.animationManager.stopActiveAnimation()
                }

                override fun mouseClicked(e: MouseEvent?) {
                    viewer.animationManager.stopActiveAnimation()
                }
            },
        )
    }

    private fun initScrollBarAdjustmentListener(scrollBar: JScrollBar) =
        scrollBar.addAdjustmentListener { e: AdjustmentEvent ->
            if (e.valueIsAdjusting) {
                viewer.animationManager.stopActiveAnimation()
                treeViewer.startHintFadeOut()
            }
        }

    val scrollSensitivity = 20.0
    val smoothingFactor = 0.5
    val smoothingWindow = 20.0
    val smoothedDeltaX = doubleArrayOf(0.0)
    val smoothedDeltaY = doubleArrayOf(0.0)
    val lastEventTime = longArrayOf(System.currentTimeMillis())

    private val zoomStep = 50

    private fun initScrollpaneWheelListener() {
        viewer.scrollpane.addMouseWheelListener { e: MouseWheelEvent ->
            val eventTime = System.currentTimeMillis()
            val timeSinceLastEvent = (eventTime - lastEventTime[0]) / 1000.0
            lastEventTime[0] = eventTime

            viewer.animationManager.stopActiveAnimation()

            if (e.isControlDown) {
                val wheelRotation = e.preciseWheelRotation
                val delta = (-wheelRotation * zoomStep).toInt()
                var newValue = viewer.scaleslider.value + delta
                newValue = max(viewer.scaleslider.minimum, min(newValue, viewer.scaleslider.maximum))
                viewer.scaleslider.value = newValue
                e.consume()
                return@addMouseWheelListener
            }

            val viewport = viewer.scrollpane.viewport ?: return@addMouseWheelListener

            val wheelRotation = e.preciseWheelRotation
            val delta = wheelRotation * scrollSensitivity

            val weight = min(1.0, timeSinceLastEvent / (smoothingWindow / 1000.0))
            if (e.isShiftDown) {
                smoothedDeltaX[0] =
                    (1 - smoothingFactor * weight) * smoothedDeltaX[0] + (smoothingFactor * weight) * delta
                smoothedDeltaY[0] = (1 - smoothingFactor * weight) * smoothedDeltaY[0]
            } else {
                smoothedDeltaY[0] =
                    (1 - smoothingFactor * weight) * smoothedDeltaY[0] + (smoothingFactor * weight) * delta
                smoothedDeltaX[0] = (1 - smoothingFactor * weight) * smoothedDeltaX[0]
            }

            val pos = viewport.viewPosition
            var newX = pos.x + smoothedDeltaX[0]
            var newY = pos.y + smoothedDeltaY[0]

            val maxX = max(0, viewer.treeViewer.width - viewport.width).toDouble()
            val maxY = max(0, viewer.treeViewer.height - viewport.height).toDouble()
            newX = max(0.0, min(newX, maxX))
            newY = max(0.0, min(newY, maxY))

            viewport.viewPosition = Point(newX.toInt(), newY.toInt())
            treeViewer.startHintFadeOut()
            e.consume()
        }
    }

    private fun initKeyListener() {
        treeViewer.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    handleKeyPress(e)
                }
            },
        )
    }

    private fun initMouseListener() {
        treeViewer.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val modelX = e.x / treeViewer.scale - treeViewer.viewportPadding
                    val modelY = e.y / treeViewer.scale - treeViewer.viewportPadding
                    val tooltipInfo = treeViewer.getTooltipInfo(treeViewer.hoveredNode)
                    if (tooltipInfo != null && tooltipInfo.bounds.contains(modelX, modelY)) {
                        cancelTooltipHideTimer()
                        val parent = treeViewer.hoveredNode?.parent
                        if (parent != null) {
                            selectAndCenterNode(parent)
                        }
                    } else {
                        val node = treeViewer.getNodeAt(e.x, e.y)
                        when (e.button) {
                            MouseEvent.BUTTON1 -> {
                                if (node != null) {
                                    if (node == viewer.selectedNode) {
                                        treeViewer.setSelectedNode(null)
                                    } else {
                                        selectAndCenterNode(node)
                                    }
                                } else {
                                    treeViewer.setSelectedNode(null)
                                }
                            }

                            MouseEvent.BUTTON3 -> {
                                ParseTreeContextualMenu.showPopupMenu(treeViewer, e)
                            }
                        }
                    }
                }
            },
        )
    }

    private fun selectAndCenterNode(node: Tree) {
        treeViewer.setSelectedNode(node)
        viewer.animationManager.centerTargetNode(node)
        treeViewer.requestFocusInWindow()
        // Avoid feeding editor-driven selection back into the editor.
        if (!viewer.ignoreEditorUpdate) {
            val service = project.getService(PreviewService::class.java)
            service.onUberElementSelected(node)
        }
    }

    private fun handleKeyPress(e: KeyEvent) {
        if (viewer.selectedNode == null || treeViewer.treeLayout == null) return
        val keyCode = e.keyCode
        viewer.selectedNode?.let {
            when (keyCode) {
                KeyEvent.VK_DOWN -> treeViewer.expandNode(it)
                KeyEvent.VK_UP -> treeViewer.collapseNode(it)
                KeyEvent.VK_LEFT -> if (it.parent != null) treeViewer.setSelectedNode(it.parent)
                KeyEvent.VK_RIGHT -> if (it.childCount > 0) treeViewer.setSelectedNode(it.getChild(0))
            }
        }
        treeViewer.requestFocusInWindow()
        treeViewer.repaint()
    }

    private fun initDragListener() {
        val mouseAdapter =
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) {
                        viewer.animationManager.stopActiveAnimation()
                        treeViewer.startHintFadeOut()
                        isDragging = true
                        holdPointOnView = e.point
                        treeViewer.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                        treeViewer.requestFocusInWindow()
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) {
                        isDragging = false
                        holdPointOnView = null
                        treeViewer.cursor = Cursor.getDefaultCursor()
                    }
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (!isDragging || holdPointOnView == null) return

                    val currentTime = System.nanoTime()
                    val timeSinceLastUpdate = (currentTime - lastDragEventUpdateTime) / 1_000_000.0

                    // Throttle drag events to approximately 60 Hz.
                    val shouldUpdate = timeSinceLastUpdate >= dragEventIntervalMs

                    if (shouldUpdate) {
                        val dragPoint = e.point
                        val deltaX = dragPoint.x - holdPointOnView!!.x
                        val deltaY = dragPoint.y - holdPointOnView!!.y

                        val viewPos = viewer.viewport.viewPosition
                        var newViewX = viewPos.x - deltaX
                        var newViewY = viewPos.y - deltaY

                        val maxViewPosX = max(0.0, (treeViewer.size.width - viewer.viewport.width).toDouble()).toInt()
                        val maxViewPosY = max(0.0, (treeViewer.size.height - viewer.viewport.height).toDouble()).toInt()

                        newViewX = newViewX.coerceIn(0, maxViewPosX)
                        newViewY = newViewY.coerceIn(0, maxViewPosY)

                        if (newViewX == 0 || newViewX == maxViewPosX) {
                            holdPointOnView = Point(dragPoint.x, holdPointOnView!!.y)
                        }
                        if (newViewY == 0 || newViewY == maxViewPosY) {
                            holdPointOnView = Point(holdPointOnView!!.x, dragPoint.y)
                        }

                        viewer.viewport.viewPosition = Point(newViewX, newViewY)
                        viewer.viewport.repaint()

                        lastDragEventUpdateTime = currentTime
                    }
                }
            }

        treeViewer.addMouseListener(mouseAdapter)
        treeViewer.addMouseMotionListener(mouseAdapter)
    }

    internal fun notifySelectionListeners(node: Tree?) {
        selectionListeners.forEach { it.onTreeNodeSelected(node) }
    }

    fun addTreeSelectionListener(listener: TreeSelectionListener) {
        selectionListeners.add(listener)
    }

    fun initializeTreeNodeSelectionListener() {
        addTreeSelectionListener(
            object : TreeSelectionListener {
                override fun onTreeNodeSelected(node: Tree?) {
                    // Avoid feeding editor-driven selection back into the editor.
                    if (!viewer.ignoreEditorUpdate) {
                        project.service<PreviewService>().onUberElementSelected(node)
                    }
                }
            },
        )
    }
}
