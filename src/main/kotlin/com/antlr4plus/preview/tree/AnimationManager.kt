package com.antlr4plus.preview.tree

import com.antlr4plus.animation.AnimationUtils
import com.antlr4plus.preview.tree.Utilities.dpToPixels
import java.awt.Component
import java.awt.Point
import java.awt.geom.Point2D
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.min
import org.antlr.v4.runtime.tree.Tree

class AnimationManager(private val treeViewer: TreeViewer, val viewer: UberTreeViewer) {
    internal var activeAnimation: AnimationUtils.Animation? = null
    internal var animationTimer: Timer? = null
    internal var isAnimating: Boolean = false

    fun centerTargetNode(node: Tree?, offsetXDp: Float = 0f, offsetYDp: Float = -12f) {
        assert(SwingUtilities.isEventDispatchThread()) { "centerTargetNode must be called on EDT" }
        if (node == null || treeViewer.treeLayout == null) return
        viewer.currentTargetNode = node
        stopActiveAnimation(clearTarget = false)
        val scrollPane = findScrollPane(treeViewer) ?: return treeViewer.setSelectedNode(node)
        val viewport = scrollPane.viewport ?: return treeViewer.setSelectedNode(node)
        val bounds = treeViewer.getBoundsOfNode(node) ?: return

        val viewportSize = viewport.extentSize
        val contentSize = treeViewer.size
        val offsetX = dpToPixels(offsetXDp)
        val offsetY = dpToPixels(offsetYDp)
        val targetX =
            (bounds.x * treeViewer.scale + bounds.width * treeViewer.scale / 2 - viewportSize.width / 2 + offsetX)
                .toInt()
        val targetY =
            (bounds.y * treeViewer.scale + bounds.height * treeViewer.scale / 2 - viewportSize.height / 2 + offsetY)
                .toInt()
        val maxScrollX = max(0, contentSize.width - viewportSize.width)
        val maxScrollY = max(0, contentSize.height - viewportSize.height)
        val clampedTargetX = max(0, min(targetX, maxScrollX))
        val clampedTargetY = max(0, min(targetY, maxScrollY))
        val startPos = viewport.viewPosition
        isAnimating = true
        val animation =
            AnimationUtils.createAnimation(
                setPosition = { x, y ->
                    viewport.viewPosition = Point(x, y)
                    viewport.repaint()
                    treeViewer.repaint()
                },
                startX = startPos.x,
                startY = startPos.y,
                targetX = clampedTargetX,
                targetY = clampedTargetY,
                easingFunction = AnimationUtils.EasingFunction.EASE_IN_OUT_QUAD,
                durationMs = 300.0,
                onComplete = {
                    treeViewer.setSelectedNode(viewer.currentTargetNode)
                    viewer.currentTargetNode = null
                    isAnimating = false
                    SwingUtilities.invokeLater { treeViewer.repaint() }
                },
            )
        activeAnimation = animation
        animation.start()
    }

    internal fun findScrollPane(component: Component?): JScrollPane? {
        var current = component
        while (current != null && current !is JScrollPane) {
            current = current.parent
        }
        return current as? JScrollPane
    }

    fun stopActiveAnimation(clearTarget: Boolean = true) {
        activeAnimation?.apply {
            stop()
            timer.actionListeners.forEach { timer.removeActionListener(it) }
            activeAnimation = null
        }
        animationTimer?.stop()
        if (clearTarget && !isAnimating) viewer.currentTargetNode = null
    }

    fun adjustScrollForZoom(
        centerPoint: Point2D,
        scrollPane: JScrollPane,
        newScale: Double,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ) {
        val viewport = scrollPane.viewport ?: return
        val viewportSize = viewport.extentSize
        val treeLayout = treeViewer.treeLayout ?: return
        val modelWidth = treeLayout.bounds.width
        val modelHeight = treeLayout.bounds.height
        val newContentWidth = (modelWidth * newScale).toInt()
        val newContentHeight = (modelHeight * newScale).toInt()
        val targetViewX = (centerPoint.x * newScale + offsetX).toInt()
        val targetViewY = (centerPoint.y * newScale + offsetY).toInt()
        val pNewX = targetViewX - viewportSize.width / 2
        val pNewY = targetViewY - viewportSize.height / 2
        val maxScrollX = max(0, newContentWidth - viewportSize.width)
        val maxScrollY = max(0, newContentHeight - viewportSize.height)
        val clampedX = pNewX.coerceIn(0, maxScrollX)
        val clampedY = pNewY.coerceIn(0, maxScrollY)
        viewport.viewPosition = Point(clampedX, clampedY)
        treeViewer.repaint()
        viewport.repaint()
    }
}
