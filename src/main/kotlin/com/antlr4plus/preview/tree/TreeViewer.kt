package com.antlr4plus.preview.tree

import com.antlr4plus.editor.EditorUtils
import com.antlr4plus.parsing.PreviewInterpreterRuleContext
import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.ui.DarculaColors
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.font.TextAttribute
import java.awt.geom.CubicCurve2D
import java.awt.geom.GeneralPath
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.text.AttributedString
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.Timer
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ErrorNode
import org.antlr.v4.runtime.tree.Tree

class TreeViewer(
    val viewer: UberTreeViewer,
    private val treeModel: TreeModel,
) : JComponent() {
    val rootTree: Tree?
        get() = treeModel.getRoot()

    val style = ViewerStyle()
    var treeLayout: BuchheimWalkerLayout<Tree>? = null
    var scale: Double = 1.0
        set(value) {
            field = if (value <= 0) 1.0 else value
            updatePreferredSize()
        }

    private val highlightUnreachedNodes: Boolean = false

    enum class ConnectionMode {
        SPLINE,
        ORTHOGONAL,
        DIRECT,
    }

    var connectionMode: ConnectionMode = ConnectionMode.SPLINE
        private set

    val useOrthogonalEdges: Boolean
        get() = connectionMode == ConnectionMode.ORTHOGONAL

    val useDirectLines: Boolean
        get() = connectionMode == ConnectionMode.DIRECT

    var arcSize: Int = 0
    var highlightedNodes: MutableMap<Tree?, Boolean?>? = mutableMapOf()
    /** Nodes differing from the chosen ambiguity tree, outlined in orange. */
    var differingNodes: MutableSet<Tree>? = null
    var boxColor: Color? = null
    var highlightedBoxColor: Color? = Color(180, 180, 180, 60)
    var differingNodeColor: Color? = Color(255, 180, 100, 180)
    var borderColor: Color? = null
    var nodeTextColor: Color? = Color.decode("#808080")
    var textColor: Color? = Color.decode("#BCBEC4")
    var edgeColor: Color = Gray._96
    private var gapBetweenLevels: Double = HardcodedConfigs.GAP_BETWEEN_LEVELS
    private var gapBetweenNodes: Double = HardcodedConfigs.GAP_BETWEEN_NODES
    internal var nodeWidthPadding: Int = HardcodedConfigs.NODE_WIDTH_PADDING
    internal var nodeHeightPadding: Int = HardcodedConfigs.NODE_HEIGHT_PADDING

    internal var hoveredNode: Tree? = null
        set(value) {
            if (field !== value) {
                field = value
                cachedTooltipInfo = null
                cachedTooltipNode = null
            }
        }

    private var cachedTooltipInfo: TooltipInfo? = null
    private var cachedTooltipNode: Tree? = null
    private var cachedTooltipViewportPos: Point? = null

    private var cachedFontMetrics: java.awt.FontMetrics? = null
    private val fontMetrics: java.awt.FontMetrics
        get() = cachedFontMetrics ?: getFontMetrics(style.mainFont).also { cachedFontMetrics = it }

    // Descendant bounds allow hit testing and painting to prune entire branches.
    private var subtreeBounds: Map<Tree, Rectangle2D.Double> = emptyMap()

    private var precomputedEdges: List<PrecomputedEdge> = emptyList()

    // Share node rectangles between edge and node rendering.
    private var cachedNodeBounds: Map<Tree, Rectangle2D.Double> = emptyMap()

    private var lastTreeClearedTime: Long = System.currentTimeMillis()
    private val emptyStateDelayMs: Long = 4000L

    // Suppress the empty-state message while loading.
    var isLoading: Boolean = false

    private var showNavigationHint = true
    private var hintOpacity = 1.0f
    private var hintFadeTimer: Timer? = null

    internal val viewportPadding: Int = 20

    init {
        if (treeModel.getRoot() != null) {
            setTree(treeModel.getRoot())
        }
        setFont(style.mainFont)
        isDoubleBuffered = true
        isFocusable = true
    }

    data class TooltipInfo(val bounds: Rectangle2D, val text: String)

    private fun isNodeVisible(node: Tree?, viewport: JViewport): Boolean {
        if (node == null || treeLayout == null) return false
        val bounds = getBoundsOfNode(node) ?: return false
        val viewRect = viewport.viewRect
        val modelViewRect =
            Rectangle2D.Double(
                viewRect.x / scale - viewportPadding,
                viewRect.y / scale - viewportPadding,
                viewRect.width / scale,
                viewRect.height / scale,
            )
        return bounds.intersects(modelViewRect)
    }

    fun getTooltipInfo(node: Tree?): TooltipInfo? {
        if (node == null) return null

        val currentViewportPos = viewer.scrollpane.viewport.viewPosition

        if (node == cachedTooltipNode && cachedTooltipInfo != null && cachedTooltipViewportPos == currentViewportPos) {
            return cachedTooltipInfo
        }

        val parent = node.parent ?: return null
        if (isNodeVisible(parent, viewer.scrollpane.viewport)) {
            cachedTooltipNode = node
            cachedTooltipInfo = null
            cachedTooltipViewportPos = currentViewportPos
            return null
        }
        val text = getText(parent)
        val fm = fontMetrics
        val textWidth = fm.stringWidth(text)
        val textHeight = fm.height
        val boxWidth = textWidth + 10.0
        val boxHeight = textHeight + 10.0
        val nodeBounds = getBoundsOfNode(node) ?: return null
        val boxX = nodeBounds.x + (nodeBounds.width - boxWidth) / 2
        val boxY = nodeBounds.y - boxHeight - 5
        val bounds = Rectangle2D.Double(boxX, boxY, boxWidth, boxHeight)
        val info = TooltipInfo(bounds, text)

        cachedTooltipNode = node
        cachedTooltipInfo = info
        cachedTooltipViewportPos = currentViewportPos
        return info
    }

    fun startHintFadeOut() {
        if (!showNavigationHint || hintOpacity <= 0f) return

        hintFadeTimer?.stop()
        hintFadeTimer =
            Timer(16) {
                    hintOpacity -= 0.016f
                    if (hintOpacity <= 0f) {
                        hintOpacity = 0f
                        showNavigationHint = false
                        hintFadeTimer?.stop()
                        hintFadeTimer = null
                    }
                    repaint()
                }
                .apply { start() }
    }

    fun resetNavigationHint() {
        hintFadeTimer?.stop()
        hintFadeTimer = null
        showNavigationHint = true
        hintOpacity = 1.0f
    }

    private val scaledTreeSize: Dimension
        get() {
            val bounds = treeLayout?.bounds
            val treeWidth = bounds?.width?.toInt() ?: 0
            val treeHeight = bounds?.height?.toInt() ?: 0
            val totalPadding = (viewportPadding * 2 * scale).toInt()
            return Dimension(
                (treeWidth * scale).toInt() + totalPadding,
                (treeHeight * scale).toInt() + totalPadding,
            )
        }

    override fun paint(g: Graphics) {
        paintInternal(g, fillBackground = true)
        paintNavigationHint(g)
    }

    /** Paints without navigation hints; disable [fillBackground] for transparent exports. */
    fun paintForExport(g: Graphics, fillBackground: Boolean = true) {
        paintInternal(g, fillBackground)
    }

    private fun paintNavigationHint(g: Graphics) {
        if (!showNavigationHint || hintOpacity <= 0f || treeLayout == null) return

        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
        )

        val viewRect = viewer.viewport.viewRect
        val hintX = viewRect.x + 20
        val hintY = viewRect.y + 30

        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, hintOpacity)

        val hintText = "Ready \u2192"
        g2.font = style.mainFont.deriveFont(14f)
        g2.color = textColor
        g2.drawString(hintText, hintX, hintY)

        g2.dispose()
    }

    private fun paintInternal(g: Graphics, fillBackground: Boolean) {
        val deviceClip = g.clipBounds ?: Rectangle(0, 0, width, height)
        val modelClip =
            Rectangle2D.Double(
                (deviceClip.x / scale) - viewportPadding,
                (deviceClip.y / scale) - viewportPadding,
                deviceClip.width / scale,
                deviceClip.height / scale,
            )

        val g2 = g.create() as Graphics2D
        if (fillBackground) {
            g2.color = background
            g2.fillRect(0, 0, width, height)
        }

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
        )
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(
            RenderingHints.KEY_FRACTIONALMETRICS,
            RenderingHints.VALUE_FRACTIONALMETRICS_ON,
        )
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.scale(scale, scale)
        g2.translate(viewportPadding, viewportPadding)

        if (treeLayout == null) {
            val elapsedMs = System.currentTimeMillis() - lastTreeClearedTime
            if (elapsedMs >= emptyStateDelayMs && !isLoading) {
                g2.color = Gray._128
                g2.font = style.mainFont.deriveFont(14f)
                val message = "Nothing to Show"
                val fm = g2.fontMetrics
                val textWidth = fm.stringWidth(message)
                val x = (width / scale - textWidth) / 2
                val y = height / scale / 2
                g2.drawString(message, x.toInt(), y.toInt())
            }
            g2.dispose()
            return
        }

        g2.stroke = BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.color = edgeColor

        // Render beyond the viewport to keep panning smooth.
        val bufferX = modelClip.width * 0.5
        val bufferY = modelClip.height * 0.5
        val expandedClip =
            Rectangle2D.Double(
                modelClip.x - bufferX,
                modelClip.y - bufferY,
                modelClip.width + 2 * bufferX,
                modelClip.height + 2 * bufferY,
            )

        // Include endpoints of visible edges even when their nodes lie outside the viewport.
        val visibleContent = collectVisibleContent(treeModel.getRoot(), expandedClip)
        paintVisibleContent(g2, visibleContent)

        val tooltipInfo = getTooltipInfo(hoveredNode)
        if (tooltipInfo != null) {
            val tooltipBox = tooltipInfo.bounds
            g2.color = background
            g2.fillRect(
                tooltipBox.x.toInt(),
                tooltipBox.y.toInt(),
                tooltipBox.width.toInt(),
                tooltipBox.height.toInt(),
            )
            g2.color = textColor
            val textX = tooltipBox.x + 5
            val textY = tooltipBox.y + 5 + g2.fontMetrics.ascent
            g2.drawString(tooltipInfo.text, textX.toInt(), textY.toInt())
        }

        g2.dispose()
    }

    fun getViewportBounds(): Rectangle? = viewer.viewport.viewRect

    fun getBoundsOfNode(node: Tree?): Rectangle2D.Double? {
        if (node == null) return null
        return treeLayout?.nodeBounds?.get(node)
    }

    /** Endpoint geometry survives connection-mode changes. */
    private data class EdgeGeometry(
        val parent: Tree,
        val child: Tree,
        val startX: Double,
        val startY: Double,
        val endX: Double,
        val endY: Double,
    )

    private data class PrecomputedEdge(
        val geometry: EdgeGeometry,
        var cachedPath: Shape? = null,
        var cachedPathBounds: Rectangle2D? = null,
    ) {
        val parent: Tree
            get() = geometry.parent

        val child: Tree
            get() = geometry.child

        val startX: Double
            get() = geometry.startX

        val startY: Double
            get() = geometry.startY

        val endX: Double
            get() = geometry.endX

        val endY: Double
            get() = geometry.endY
    }

    private data class LineSegment(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
    ) {
        val bounds: Rectangle2D.Double by lazy {
            Rectangle2D.Double(
                minOf(x1, x2),
                minOf(y1, y2),
                kotlin.math.abs(x2 - x1).coerceAtLeast(1.0),
                kotlin.math.abs(y2 - y1).coerceAtLeast(1.0),
            )
        }
    }

    /** Shared parent stem and horizontal bar, with one vertical segment per child. */
    private data class OrthogonalSegmentsForParent(
        val children: List<Tree>,
        val parentVertical: LineSegment,
        val horizontal: LineSegment,
        val childVerticals: List<LineSegment>,
    )

    private var cachedOrthogonalSegments: Map<Tree, OrthogonalSegmentsForParent>? = null

    private class VisibleContent {
        val edges = mutableListOf<PrecomputedEdge>()
        val nodesToDraw = mutableSetOf<Tree>()
        val orthogonalSegments = mutableSetOf<LineSegment>()
    }

    private data class EdgeComputationResult(
        val edges: List<PrecomputedEdge>,
        val nodeBounds: Map<Tree, Rectangle2D.Double>,
    )

    /** Captures edge endpoints and node bounds; paths are created lazily. */
    private fun computeAllEdgesAndBounds(root: Tree?): EdgeComputationResult {
        if (root == null) return EdgeComputationResult(emptyList(), emptyMap())
        val edges = mutableListOf<PrecomputedEdge>()
        val nodeBounds = mutableMapOf<Tree, Rectangle2D.Double>()
        val pending = ArrayDeque<Tree>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val parent = pending.removeLast()
            val parentBounds = getBoundsOfNode(parent) ?: continue
            nodeBounds[parent] = parentBounds
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                getBoundsOfNode(child)?.let { childBounds ->
                    nodeBounds[child] = childBounds

                    val x1 = parentBounds.centerX
                    val y1 = parentBounds.maxY
                    val x2 = childBounds.centerX
                    val y2 = childBounds.minY

                    val geometry =
                        EdgeGeometry(
                            parent = parent,
                            child = child,
                            startX = x1,
                            startY = y1,
                            endX = x2,
                            endY = y2,
                        )
                    edges.add(PrecomputedEdge(geometry))

                    pending.addLast(child)
                }
            }
        }
        return EdgeComputationResult(edges, nodeBounds)
    }

    /** Caches paths until the connection mode changes. */
    private fun getOrCreateEdgePath(edge: PrecomputedEdge): Shape {
        edge.cachedPath?.let {
            return it
        }

        val path = createEdgePath(edge.startX, edge.startY, edge.endX, edge.endY)
        edge.cachedPath = path
        edge.cachedPathBounds = path.bounds2D
        return path
    }

    private fun getOrCreateEdgePathBounds(edge: PrecomputedEdge): Rectangle2D {
        edge.cachedPathBounds?.let {
            return it
        }

        getOrCreateEdgePath(edge)
        return edge.cachedPathBounds!!
    }

    private fun invalidateEdgePaths() {
        for (edge in precomputedEdges) {
            edge.cachedPath = null
            edge.cachedPathBounds = null
        }
        cachedOrthogonalSegments = null
    }

    /** Groups edges by parent so orthogonal stems and bars are drawn once. */
    private fun getOrCreateOrthogonalSegments(): Map<Tree, OrthogonalSegmentsForParent> {
        cachedOrthogonalSegments?.let {
            return it
        }

        val edgesByParent = precomputedEdges.groupBy { it.parent }

        val segments = mutableMapOf<Tree, OrthogonalSegmentsForParent>()

        for ((parent, edges) in edgesByParent) {
            if (edges.isEmpty()) continue

            val parentX = edges.first().startX
            val parentY = edges.first().startY

            val childEndpoints = edges.map { Triple(it.child, it.endX, it.endY) }

            // Siblings share a layout level, so use the first child to position the horizontal bar.
            val firstChildY = childEndpoints.first().third
            val midY = (parentY + firstChildY) / 2.0

            val parentVertical = LineSegment(parentX, parentY, parentX, midY)

            val minChildX = childEndpoints.minOf { it.second }
            val maxChildX = childEndpoints.maxOf { it.second }

            val horizontalMinX = minOf(minChildX, parentX)
            val horizontalMaxX = maxOf(maxChildX, parentX)
            val horizontal = LineSegment(horizontalMinX, midY, horizontalMaxX, midY)

            val childVerticals = childEndpoints.map { (_, childX, childY) ->
                LineSegment(childX, midY, childX, childY)
            }

            segments[parent] =
                OrthogonalSegmentsForParent(
                    children = edges.map { it.child },
                    parentVertical = parentVertical,
                    horizontal = horizontal,
                    childVerticals = childVerticals,
                )
        }

        cachedOrthogonalSegments = segments
        return segments
    }

    /** Collects intersecting node bounds, visible edge bounds and their endpoint nodes. */
    private fun collectVisibleContent(root: Tree?, viewport: Rectangle2D): VisibleContent {
        val content = VisibleContent()
        if (root == null) return content

        collectNodesInViewport(root, viewport, content)

        if (useOrthogonalEdges && !useDirectLines) {
            collectVisibleOrthogonalSegments(viewport, content)
        } else {
            for (edge in precomputedEdges) {
                val startInViewport = viewport.contains(edge.startX, edge.startY)
                val endInViewport = viewport.contains(edge.endX, edge.endY)
                val pathBounds = getOrCreateEdgePathBounds(edge)
                val pathIntersects = pathBounds.intersects(viewport)

                if (startInViewport || endInViewport || pathIntersects) {
                    content.edges.add(edge)
                    content.nodesToDraw.add(edge.parent)
                    content.nodesToDraw.add(edge.child)
                }
            }
        }

        return content
    }

    private fun collectVisibleOrthogonalSegments(viewport: Rectangle2D, content: VisibleContent) {
        val segmentsByParent = getOrCreateOrthogonalSegments()

        for ((parent, parentSegments) in segmentsByParent) {

            var anyVisible = false

            if (parentSegments.parentVertical.bounds.intersects(viewport)) {
                content.orthogonalSegments.add(parentSegments.parentVertical)
                anyVisible = true
            }

            if (parentSegments.horizontal.bounds.intersects(viewport)) {
                content.orthogonalSegments.add(parentSegments.horizontal)
                anyVisible = true
            }

            for (childVertical in parentSegments.childVerticals) {
                if (childVertical.bounds.intersects(viewport)) {
                    content.orthogonalSegments.add(childVertical)
                    anyVisible = true
                }
            }

            // Keep endpoints available whenever part of a connection is visible.
            if (anyVisible) {
                content.nodesToDraw.add(parent)
                content.nodesToDraw.addAll(parentSegments.children)
            }
        }
    }

    private fun createEdgePath(x1: Double, y1: Double, x2: Double, y2: Double): Shape =
        when {
            useDirectLines -> {
                Line2D.Double(x1, y1, x2, y2)
            }
            useOrthogonalEdges -> {
                val midY = (y1 + y2) / 2.0
                GeneralPath().apply {
                    moveTo(x1, y1)
                    lineTo(x1, midY)
                    lineTo(x2, midY)
                    lineTo(x2, y2)
                }
            }
            else -> {
                CubicCurve2D.Double(x1, y1, x1, y2, x2, y1, x2, y2)
            }
        }

    /** Collects intersecting nodes, pruning branches by their descendant bounds. */
    private fun collectNodesInViewport(
        root: Tree?,
        viewport: Rectangle2D,
        content: VisibleContent,
    ) {
        if (root == null) return
        val pending = ArrayDeque<Tree>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            val subtreeBound = subtreeBounds[node]
            if (subtreeBound != null && !subtreeBound.intersects(viewport)) continue

            val nodeBounds = getBoundsOfNode(node)
            if (nodeBounds != null && nodeBounds.intersects(viewport)) {
                content.nodesToDraw.add(node)
            }
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let(pending::addLast)
            }
        }
    }

    private fun paintVisibleContent(g: Graphics2D, content: VisibleContent) {
        if (useOrthogonalEdges && !useDirectLines) {
            for ((x1, y1, x2, y2) in content.orthogonalSegments) {
                g.drawLine(
                    x1.toInt(),
                    y1.toInt(),
                    x2.toInt(),
                    y2.toInt(),
                )
            }
        } else {
            for (edge in content.edges) {
                g.draw(getOrCreateEdgePath(edge))
            }
        }

        for (node in content.nodesToDraw) {
            val bounds = cachedNodeBounds[node] ?: getBoundsOfNode(node)
            if (bounds != null) {
                paintBoxWithBounds(g, node, bounds)
            }
        }
    }

    private fun paintBoxWithBounds(g: Graphics, tree: Tree, box: Rectangle2D.Double) {
        if (tree == viewer.selectedNode && scale <= HardcodedConfigs.TREE_SELECTION_BOX_FILL_THRESHOLD) {
            g.color = JBColor.BLUE
            g.fillRoundRect(
                box.x.toInt(),
                box.y.toInt(),
                box.width.toInt(),
                box.height.toInt(),
                6,
                6,
            )
        } else {
            customPaintBoxWithBounds(g, tree, box)
        }
        paintBoxOutlines(g, tree, box)
    }

    private fun paintBoxOutlines(g: Graphics, tree: Tree?, box: Rectangle2D.Double) {
        val isDiffering = differingNodes?.contains(tree) == true
        val isUnreachedPreview = tree is PreviewInterpreterRuleContext && highlightUnreachedNodes && !tree.reached
        if (isDiffering || isUnreachedPreview) {
            val g2d = g as Graphics2D
            val oldStroke = g2d.stroke
            val pad = 2
            val cornerRadius = 6
            g2d.stroke = BasicStroke(1.5f)
            g2d.color = differingNodeColor
            g2d.drawRoundRect(
                box.x.toInt() - pad,
                box.y.toInt() - pad,
                box.width.toInt() + pad * 2 - 1,
                box.height.toInt() + pad * 2 - 1,
                cornerRadius,
                cornerRadius,
            )
            g2d.stroke = oldStroke
        }
        if (tree == viewer.selectedNode && scale > HardcodedConfigs.TREE_SELECTION_BOX_FILL_THRESHOLD) {
            g.color = JBColor.BLUE
            val g2d = g as Graphics2D
            g2d.stroke = BasicStroke(1.0f)
            g2d.drawRoundRect(
                box.x.toInt() - 3,
                box.y.toInt() - 3,
                box.width.toInt() + 6,
                box.height.toInt() + 7,
                6,
                6,
            )
            g2d.stroke = BasicStroke(1.0f)
        }
    }

    private fun customPaintBoxWithBounds(g: Graphics, tree: Tree?, box: Rectangle2D.Double) {
        val failed = ruleFailedAndMatchedNothing(tree)
        if (isHighlighted(tree) || boxColor != null) {
            g.color =
                when {
                    isHighlighted(tree) -> highlightedBoxColor
                    else -> boxColor
                }
            g.fillRoundRect(
                box.x.toInt(),
                box.y.toInt(),
                box.width.toInt(),
                box.height.toInt(),
                arcSize,
                arcSize,
            )
        }
        if (borderColor != null) {
            g.color = borderColor
            g.drawRoundRect(
                box.x.toInt(),
                box.y.toInt(),
                box.width.toInt(),
                box.height.toInt(),
                arcSize,
                arcSize,
            )
        }
        val isLeaf = tree != null && tree.childCount == 0
        val leafColor =
            when {
                isErrorTree(tree) || failed -> DarculaColors.RED
                else -> textColor
            }
        val nonLeafColor =
            when {
                isErrorTree(tree) || failed -> DarculaColors.RED
                else -> nodeTextColor
            }

        val s = getText(tree)
        val lines = s.split("\n").filter { it.isNotEmpty() }.toTypedArray()
        val m = fontMetrics
        val x = box.x.toInt() + arcSize / 2 + nodeWidthPadding
        var y = box.y.toInt() + m.ascent + m.leading + 1 + nodeHeightPadding
        val g2d = g as? Graphics2D

        lines.forEach { line ->
            // Render token type and text together to preserve spacing across color changes.
            if (isLeaf && line.contains(":") && g2d != null) {
                val colonIndex = line.indexOf(":")

                if (colonIndex >= 0 && line.length > 1) {
                    val attrStr = AttributedString(line)
                    // Match the font used by VariableExtentProvider.
                    attrStr.addAttribute(TextAttribute.FONT, style.mainFont)
                    attrStr.addAttribute(TextAttribute.FOREGROUND, nonLeafColor, 0, colonIndex + 1)
                    if (colonIndex + 1 < line.length) {
                        attrStr.addAttribute(
                            TextAttribute.FOREGROUND,
                            leafColor,
                            colonIndex + 1,
                            line.length,
                        )
                    }
                    g2d.drawString(attrStr.iterator, x, y)
                } else {
                    g.color = leafColor
                    g.drawString(line, x, y)
                }
            } else {
                g.color = if (isLeaf) leafColor else nonLeafColor
                g.drawString(line, x, y)
            }
            y += m.height
        }
    }

    private fun findNodeAt(node: Tree?, x: Int, y: Int): Tree? {
        if (node == null) return null
        val scaledX = x / scale - viewportPadding
        val scaledY = y / scale - viewportPadding

        subtreeBounds[node]?.let { subtreeBound ->
            val inflatedSubtreeBounds =
                Rectangle2D.Double(
                    subtreeBound.x - 5 / scale,
                    subtreeBound.y - 5 / scale,
                    subtreeBound.width + 10 / scale,
                    subtreeBound.height + 10 / scale,
                )
            if (!inflatedSubtreeBounds.contains(scaledX, scaledY)) {
                return null
            }
        }

        val bounds = getBoundsOfNode(node) ?: return null
        val inflatedBounds =
            Rectangle2D.Double(
                bounds.x - 5 / scale,
                bounds.y - 5 / scale,
                bounds.width + 10 / scale,
                bounds.height + 10 / scale,
            )
        if (inflatedBounds.contains(scaledX, scaledY)) return node
        for (i in 0 until node.childCount) {
            val result = findNodeAt(node.getChild(i), x, y)
            if (result != null) return result
        }
        return null
    }

    fun paintBox(g: Graphics, tree: Tree?) {
        val box = getBoundsOfNode(tree) ?: return
        if (tree == viewer.selectedNode && scale <= HardcodedConfigs.TREE_SELECTION_BOX_FILL_THRESHOLD) {
            g.color = JBColor.BLUE
            g.fillRoundRect(
                box.x.toInt(),
                box.y.toInt(),
                box.width.toInt(),
                box.height.toInt(),
                6,
                6,
            )
        } else {
            customPaintBox(g, tree)
        }
        paintBoxOutlines(g, tree, box)
    }

    fun getNodeAt(x: Int, y: Int): Tree? {
        if (treeLayout == null || rootTree == null) return null
        return findNodeAt(rootTree, x, y)
    }

    private fun isHighlighted(node: Tree?): Boolean = highlightedNodes?.containsKey(node) == true

    private fun isErrorTree(tree: Tree?): Boolean =
        when (tree) {
            is ErrorNode -> true
            is KotlinTreeAdapter -> tree.isErrorNode()
            else -> false
        }

    private fun ruleFailedAndMatchedNothing(tree: Tree?): Boolean =
        when (tree) {
            is ParserRuleContext ->
                tree.exception != null && tree.stop != null && tree.stop.tokenIndex < tree.start.tokenIndex
            is KotlinTreeAdapter -> tree.ruleFailedAndMatchedNothing()
            else -> false
        }

    private fun customPaintBox(g: Graphics, tree: Tree?) {
        val box = getBoundsOfNode(tree) ?: return
        customPaintBoxWithBounds(g, tree, box)
    }

    private fun updatePreferredSize() {
        preferredSize = scaledTreeSize
        invalidate()
        parent?.validate()
        repaint()
    }

    fun getText(tree: Tree?): String = treeModel.getText(tree)

    private fun repaintNode(node: Tree) {
        val bounds = getBoundsOfNode(node) ?: return
        val paddingOffset = (viewportPadding * scale).toInt()
        val repaintArea =
            Rectangle(
                (bounds.x * scale).toInt() + paddingOffset - 10,
                (bounds.y * scale).toInt() + paddingOffset - 10,
                (bounds.width * scale).toInt() + 20,
                (bounds.height * scale).toInt() + 20,
            )
        repaint(repaintArea)
    }

    fun setSelectedNode(node: Tree?) {
        val oldNode = viewer.selectedNode
        viewer.selectedNode = node
        viewer.inputHandler.notifySelectionListeners(node)
        oldNode?.let { repaintNode(it) }
        node?.let { repaintNode(it) }

        if (node == null && oldNode != null) {
            runWriteAction {
                viewer.project.service<com.antlr4plus.previewService.PreviewService>().state.g4pInputFile?.let {
                    virtualFile ->
                    EditorUtils.clearSelection(viewer.project, virtualFile)
                }
            }
        }
    }

    fun setTree(root: Tree?) {
        treeModel.setTree(root)
        if (root != null) {
            treeLayout =
                BuchheimWalkerLayout(
                        root = root,
                        extentProvider = VariableExtentProvider(this),
                        horizontalGap = gapBetweenNodes,
                        verticalSpacing = gapBetweenLevels,
                    )
                    .layout()
            subtreeBounds = computeSubtreeBounds(root)
            val edgeResult = computeAllEdgesAndBounds(root)
            precomputedEdges = edgeResult.edges
            cachedNodeBounds = edgeResult.nodeBounds
            cachedOrthogonalSegments = null
            updatePreferredSize()
            resetNavigationHint()
            viewer.selectedNode = null
            viewer.inputHandler.notifySelectionListeners(null)
        } else {
            treeLayout = null
            subtreeBounds = emptyMap()
            precomputedEdges = emptyList()
            cachedNodeBounds = emptyMap()
            cachedOrthogonalSegments = null
            lastTreeClearedTime = System.currentTimeMillis()
            viewer.selectedNode = null
            viewer.inputHandler.notifySelectionListeners(null)
            repaint()
        }
        hoveredNode = null
    }

    /** Changes edge rendering while retaining layout geometry; cached paths are invalidated. */
    fun setConnectionMode(mode: ConnectionMode) {
        if (connectionMode != mode) {
            connectionMode = mode
            invalidateEdgePaths()
            repaint()
        }
    }

    /** Captured metrics for background measurement; do not mutate [charWidths] after publication. */
    data class FontMetricsSnapshot(
        val charWidths: IntArray, // Widths for character codes 0–255.
        val defaultCharWidth: Int,
        val fontHeight: Int,
        val nodeWidthPadding: Int,
        val nodeHeightPadding: Int,
    ) {
        /** Approximates width by summing cached character widths, with a fallback for other characters. */
        fun stringWidth(s: String): Int = s.sumOf { char ->
            if (char.code < charWidths.size) charWidths[char.code] else defaultCharWidth
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as FontMetricsSnapshot

            if (defaultCharWidth != other.defaultCharWidth) return false
            if (fontHeight != other.fontHeight) return false
            if (nodeWidthPadding != other.nodeWidthPadding) return false
            if (nodeHeightPadding != other.nodeHeightPadding) return false
            if (!charWidths.contentEquals(other.charWidths)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = defaultCharWidth
            result = 31 * result + fontHeight
            result = 31 * result + nodeWidthPadding
            result = 31 * result + nodeHeightPadding
            result = 31 * result + charWidths.contentHashCode()
            return result
        }
    }

    /** Capture on the EDT before measuring nodes in the background. */
    fun captureFontMetrics(): FontMetricsSnapshot {
        val fontMetrics = getFontMetrics(style.mainFont)
        val charWidths =
            IntArray(256) { char ->
                fontMetrics.charWidth(char)
            }
        return FontMetricsSnapshot(
            charWidths = charWidths,
            defaultCharWidth = fontMetrics.charWidth('M'),
            fontHeight = fontMetrics.height,
            nodeWidthPadding = nodeWidthPadding,
            nodeHeightPadding = nodeHeightPadding,
        )
    }

    /**
     * Measures without Swing access using a captured snapshot. [getTextForNode] must be safe on the calling thread; the
     * viewer provider may not yet be installed.
     */
    fun computeNodeExtentsWithSnapshot(
        root: Tree,
        fontSnapshot: FontMetricsSnapshot,
        getTextForNode: (Tree?) -> String,
    ): Map<Tree, CachedExtentProvider.NodeExtent> = measureTreeNodeExtents(root, fontSnapshot, getTextForNode)

    /** Computes from cached extents; install the result with [setTreeWithLayout] on the EDT. */
    fun computeTreeLayoutWithCachedExtents(
        root: Tree,
        cachedExtents: Map<Tree, CachedExtentProvider.NodeExtent>,
    ): BuchheimWalkerLayout<Tree> =
        BuchheimWalkerLayout(
                root = root,
                extentProvider = CachedExtentProvider(cachedExtents),
                horizontalGap = gapBetweenNodes,
                verticalSpacing = gapBetweenLevels,
            )
            .layout()

    /**
     * Installs a layout on the EDT and builds edge geometry. Pass [precomputedSubtreeBounds] to avoid computing
     * descendant bounds here.
     */
    fun setTreeWithLayout(
        root: Tree,
        precomputedLayout: BuchheimWalkerLayout<Tree>,
        precomputedSubtreeBounds: Map<Tree, Rectangle2D.Double>? = null,
    ) {
        treeModel.setTree(root)
        treeLayout = precomputedLayout
        subtreeBounds = precomputedSubtreeBounds ?: computeSubtreeBounds(root)
        val edgeResult = computeAllEdgesAndBounds(root)
        precomputedEdges = edgeResult.edges
        cachedNodeBounds = edgeResult.nodeBounds
        cachedOrthogonalSegments = null
        updatePreferredSize()
        resetNavigationHint()
        viewer.selectedNode = null
        viewer.inputHandler.notifySelectionListeners(null)
        hoveredNode = null
    }

    /** Bounds of each node and its descendants, used to prune searches. */
    private fun computeSubtreeBounds(root: Tree): Map<Tree, Rectangle2D.Double> =
        collectSubtreeBounds(root, ::getBoundsOfNode)

    /** Computes descendant bounds without Swing access; keep the tree and layout stable while traversing. */
    fun computeSubtreeBoundsWithLayout(
        root: Tree,
        layout: BuchheimWalkerLayout<Tree>,
    ): Map<Tree, Rectangle2D.Double> = collectSubtreeBounds(root) { node -> layout.nodeBounds[node] }

    internal fun expandNode(node: Tree?) {
        if (node is ParserRuleContext || (node is KotlinTreeAdapter && node.isParserRuleContext())) {
            repaint()
        }
    }

    internal fun collapseNode(node: Tree?) {
        if (node is ParserRuleContext || (node is KotlinTreeAdapter && node.isParserRuleContext())) {
            repaint()
        }
    }

    fun findCentermostNode(viewport: JViewport): Tree? {
        if (treeLayout == null || rootTree == null) return null
        val viewportBounds = viewport.viewRect
        val modelBounds =
            Rectangle2D.Double(
                viewportBounds.x / scale - viewportPadding,
                viewportBounds.y / scale - viewportPadding,
                viewportBounds.width / scale,
                viewportBounds.height / scale,
            )
        val viewportCenterX = modelBounds.x + modelBounds.width / 2.0
        val viewportCenterY = modelBounds.y + modelBounds.height / 2.0

        var closestVisible: Tree? = null
        var minDistanceSquaredVisible = Double.MAX_VALUE
        var closestOverall: Tree? = null
        var minDistanceSquaredOverall = Double.MAX_VALUE

        val pending = ArrayDeque<Tree>()
        pending.addLast(rootTree ?: return null)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            val bounds = getBoundsOfNode(node) ?: continue
            val nodeCenterX = bounds.x + bounds.width / 2.0
            val nodeCenterY = bounds.y + bounds.height / 2.0
            val dx = viewportCenterX - nodeCenterX
            val dy = viewportCenterY - nodeCenterY
            val distanceSquared = dx * dx + dy * dy
            val isVisible = bounds.intersects(modelBounds)

            if (isVisible && distanceSquared < minDistanceSquaredVisible) {
                minDistanceSquaredVisible = distanceSquared
                closestVisible = node
            }
            if (distanceSquared < minDistanceSquaredOverall) {
                minDistanceSquaredOverall = distanceSquared
                closestOverall = node
            }
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let(pending::addLast)
            }
        }
        return closestVisible ?: closestOverall
    }
}
