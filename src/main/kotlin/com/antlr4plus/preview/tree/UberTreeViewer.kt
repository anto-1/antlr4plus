package com.antlr4plus.preview.tree

import com.antlr4plus.color.brighten
import com.antlr4plus.color.buttonColor
import com.antlr4plus.icons.Icons
import com.antlr4plus.notifications.NotificationsUtils
import com.antlr4plus.preview.PreviewButtonInteraction
import com.antlr4plus.preview.containsCircularPreviewPoint
import com.antlr4plus.preview.findTreeNodeAtOffset
import com.antlr4plus.preview.formatTreeText
import com.antlr4plus.preview.paintCopyTreeButton
import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.geom.Arc2D
import java.awt.geom.CubicCurve2D
import java.awt.geom.Ellipse2D
import java.awt.geom.QuadCurve2D
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import kotlin.math.cos
import kotlin.math.sin
import org.antlr.v4.runtime.tree.Tree

open class UberTreeViewer(
    val project: Project,
    ruleNames: List<String>?,
    tree: Tree?,
) {
    val treeModel = TreeModel(ruleNames, tree)
    var treeViewer = TreeViewer(this, treeModel)
    val animationManager = AnimationManager(treeViewer, this)

    lateinit var scrollpane: ScrollPane
    lateinit var scaleslider: JSlider
    lateinit var viewport: JViewport

    lateinit var inputHandler: InputHandler

    internal var selectedNode: Tree? = null
    internal var currentTargetNode: Tree? = null

    /** Follows the caret in the .g4p editor when enabled. */
    var cursorFollowEnabled = false
        private set

    /** Prevents programmatic tree selection from updating the editor recursively. */
    internal var ignoreEditorUpdate = false

    lateinit var cursorFollowFab: CursorFollowFab

    lateinit var copyTreeFab: CopyTreeFab

    fun resetCursorFollowMode() {
        cursorFollowEnabled = false
        if (::cursorFollowFab.isInitialized) {
            cursorFollowFab.isActive = false
        }
    }

    /** Exports indented tree branches, with terminal labels in angle brackets. */
    fun generateTreeText(): String =
        formatTreeText(treeViewer.rootTree) { treeModel.treeTextProvider?.getText(it) ?: it.toString() }

    fun copyTreeToClipboard() {
        val treeText = generateTreeText()
        if (treeText.isNotEmpty()) {
            val selection = StringSelection(treeText)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            NotificationsUtils.showToast(project, "Tree structure copied to clipboard")
        }
    }

    fun resetData() {
        treeViewer.setTree(null)
        treeViewer.scale = 1.0
        scaleslider.value = ((treeViewer.scale - 1.0) * 1000).toInt()
        scrollpane.updateScrollIncrements(treeViewer.scale)
    }

    /** Selects and centers a matching node without feeding selection back into the editor. */
    fun selectNodeAtOffset(editorOffset: Int) {
        val root = treeViewer.rootTree ?: return
        val node = findTreeNodeAtOffset(root, editorOffset) ?: return

        ignoreEditorUpdate = true
        treeViewer.setSelectedNode(node)
        animationManager.centerTargetNode(node)
        ignoreEditorUpdate = false
    }

    lateinit var splineFab: FloatingActionButton
    lateinit var orthogonalFab: FloatingActionButton
    lateinit var directLinesFab: FloatingActionButton

    lateinit var edgeColorPicker: ColorPickerButton
    lateinit var nodeColorPicker: ColorPickerButton
    lateinit var textColorPicker: ColorPickerButton
    lateinit var backgroundColorPicker: ColorPickerButton
    lateinit var resetColorsButton: ResetColorsButton
    lateinit var lightModeButton: LightModeButton

    companion object {
        fun createUberPanel(
            project: Project,
            parentDisposable: Disposable = project,
        ): Pair<UberTreeViewer, JPanel> {
            val uber = UberTreeViewer(project, null, null)
            val scaleSlider =
                JSlider(
                    JSlider.HORIZONTAL,
                    ((HardcodedConfigs.MIN_SCALE - 1.0) * 1000).toInt(),
                    ((HardcodedConfigs.MAX_SCALE - 1.0) * 1000).toInt(),
                    ((uber.treeViewer.scale - 1.0) * 1000).toInt(),
                )
            val scrollPane = ScrollPane(uber.treeViewer)
            uber.scrollpane = scrollPane
            uber.viewport = scrollPane.viewport
            uber.scaleslider = scaleSlider
            uber.scrollpane.updateScrollIncrements(uber.treeViewer.scale)
            scrollPane.border = null
            uber.inputHandler = InputHandler(project, uber.treeViewer, uber)

            val panelBg = UIManager.getColor("Panel.background") ?: Color(60, 63, 65)
            val isLightTheme = (panelBg.red + panelBg.green + panelBg.blue) / 3 > 128
            if (uber.treeViewer.textColor == null) {
                uber.treeViewer.textColor = if (isLightTheme) Color.BLACK else Color.decode("#BCBEC4")
            }
            if (uber.treeViewer.nodeTextColor == null) {
                uber.treeViewer.nodeTextColor = if (isLightTheme) Color.decode("#606060") else Color.decode("#808080")
            }

            val splineFab = FloatingActionButton(IconType.SPLINE)
            val orthogonalFab = FloatingActionButton(IconType.ORTHOGONAL)
            val directLinesFab = FloatingActionButton(IconType.DIRECT_LINES)

            uber.splineFab = splineFab
            uber.orthogonalFab = orthogonalFab
            uber.directLinesFab = directLinesFab

            fun colorToHex(color: Color): String = String.format("#%02X%02X%02X", color.red, color.green, color.blue)

            fun updateFabStates() {
                val mode = uber.treeViewer.connectionMode
                splineFab.isActive = (mode == TreeViewer.ConnectionMode.SPLINE)
                orthogonalFab.isActive = (mode == TreeViewer.ConnectionMode.ORTHOGONAL)
                directLinesFab.isActive = (mode == TreeViewer.ConnectionMode.DIRECT)
            }

            fun saveConnectionMode(mode: TreeViewer.ConnectionMode) {
                val previewService = project.service<PreviewService>()
                previewService.state.liveSettings?.treeConnectionMode = mode.name
            }

            splineFab.toolTipText = "Spline connections (default)"
            splineFab.isActive = true
            splineFab.onClickAction = {
                uber.treeViewer.setConnectionMode(TreeViewer.ConnectionMode.SPLINE)
                updateFabStates()
                saveConnectionMode(TreeViewer.ConnectionMode.SPLINE)
            }

            orthogonalFab.toolTipText = "Orthogonal connections"
            orthogonalFab.onClickAction = {
                uber.treeViewer.setConnectionMode(TreeViewer.ConnectionMode.ORTHOGONAL)
                updateFabStates()
                saveConnectionMode(TreeViewer.ConnectionMode.ORTHOGONAL)
            }

            directLinesFab.toolTipText = "Direct line connections"
            directLinesFab.onClickAction = {
                uber.treeViewer.setConnectionMode(TreeViewer.ConnectionMode.DIRECT)
                updateFabStates()
                saveConnectionMode(TreeViewer.ConnectionMode.DIRECT)
            }

            val edgeColorPicker =
                ColorPickerButton(
                    ColorPickerType.EDGE,
                    uber.treeViewer.edgeColor,
                ) { color ->
                    uber.treeViewer.edgeColor = color
                    uber.treeViewer.repaint()
                    val previewService = project.service<PreviewService>()
                    previewService.state.liveSettings?.treeEdgeColor = colorToHex(color)
                }
            uber.edgeColorPicker = edgeColorPicker
            edgeColorPicker.toolTipText = "Edge color"

            val nodeColorPicker =
                ColorPickerButton(
                    ColorPickerType.NODE,
                    uber.treeViewer.nodeTextColor ?: Color.decode("#808080"),
                ) { color ->
                    uber.treeViewer.nodeTextColor = color
                    uber.treeViewer.repaint()
                    val previewService = project.service<PreviewService>()
                    previewService.state.liveSettings?.treeNodeColor = colorToHex(color)
                }
            uber.nodeColorPicker = nodeColorPicker
            nodeColorPicker.toolTipText = "Node text color (dimmer)"

            val defaultTextColor = if (isLightTheme) Color.BLACK else Color.decode("#BCBEC4")
            val textColorPicker =
                ColorPickerButton(
                    ColorPickerType.TEXT,
                    uber.treeViewer.textColor ?: defaultTextColor,
                ) { color ->
                    uber.treeViewer.textColor = color
                    uber.treeViewer.repaint()
                    val previewService = project.service<PreviewService>()
                    previewService.state.liveSettings?.treeTextColor = colorToHex(color)
                }
            uber.textColorPicker = textColorPicker
            textColorPicker.toolTipText = "Text color (brighter)"

            val backgroundColorPicker =
                ColorPickerButton(
                    ColorPickerType.BACKGROUND,
                    uber.treeViewer.background ?: Color(60, 63, 65),
                ) { color ->
                    uber.treeViewer.background = color
                    uber.treeViewer.repaint()
                    val previewService = project.service<PreviewService>()
                    previewService.state.liveSettings?.treeBackgroundColor = colorToHex(color)
                }
            uber.backgroundColorPicker = backgroundColorPicker
            backgroundColorPicker.toolTipText = "Background color"

            val defaultEdgeColor = if (isLightTheme) Color(153, 153, 153) else Gray._96
            val defaultNodeTextColor = if (isLightTheme) Color.decode("#606060") else Color.decode("#808080")

            val resetColorsButton = ResetColorsButton {
                uber.treeViewer.edgeColor = defaultEdgeColor
                uber.treeViewer.nodeTextColor = defaultNodeTextColor
                uber.treeViewer.textColor = defaultTextColor
                uber.treeViewer.background = UIManager.getColor("Panel.background") ?: Color(60, 63, 65)
                edgeColorPicker.currentColor = defaultEdgeColor
                nodeColorPicker.currentColor = defaultNodeTextColor
                textColorPicker.currentColor = defaultTextColor
                backgroundColorPicker.currentColor = uber.treeViewer.background
                uber.treeViewer.repaint()
                // Null settings restore theme defaults.
                val previewService = project.service<PreviewService>()
                previewService.state.liveSettings?.treeEdgeColor = null
                previewService.state.liveSettings?.treeNodeColor = null
                previewService.state.liveSettings?.treeTextColor = null
                previewService.state.liveSettings?.treeBackgroundColor = null
            }
            uber.resetColorsButton = resetColorsButton
            resetColorsButton.toolTipText = "Reset to theme defaults"

            val lightModeButton = LightModeButton {
                val lightBackground = Color.WHITE
                val lightEdge = Color.decode("#404040")
                val lightNodeText = Color.decode("#606060")
                val lightText = Color.decode("#000000")
                uber.treeViewer.background = lightBackground
                uber.treeViewer.edgeColor = lightEdge
                uber.treeViewer.nodeTextColor = lightNodeText
                uber.treeViewer.textColor = lightText
                backgroundColorPicker.currentColor = lightBackground
                edgeColorPicker.currentColor = lightEdge
                nodeColorPicker.currentColor = lightNodeText
                textColorPicker.currentColor = lightText
                uber.treeViewer.repaint()
                val previewService = project.service<PreviewService>()
                previewService.state.liveSettings?.treeBackgroundColor = colorToHex(lightBackground)
                previewService.state.liveSettings?.treeEdgeColor = colorToHex(lightEdge)
                previewService.state.liveSettings?.treeNodeColor = colorToHex(lightNodeText)
                previewService.state.liveSettings?.treeTextColor = colorToHex(lightText)
            }
            uber.lightModeButton = lightModeButton
            lightModeButton.toolTipText = "Light mode (white background, black text)"

            val cursorFollowFab = CursorFollowFab()
            cursorFollowFab.isActive = uber.cursorFollowEnabled
            cursorFollowFab.toolTipText = "Toggle caret follow mode"
            cursorFollowFab.onClickAction = {
                uber.cursorFollowEnabled = !uber.cursorFollowEnabled
                cursorFollowFab.isActive = uber.cursorFollowEnabled
            }
            uber.cursorFollowFab = cursorFollowFab

            val copyTreeFab = CopyTreeFab()
            copyTreeFab.toolTipText = "Copy tree structure to clipboard"
            copyTreeFab.onClickAction = {
                uber.copyTreeToClipboard()
            }
            uber.copyTreeFab = copyTreeFab

            val containerPanel =
                object : JPanel(null) {
                    // Children overlap; disable optimized drawing so floating buttons repaint
                    // correctly.
                    override fun isOptimizedDrawingEnabled(): Boolean = false

                    override fun doLayout() {
                        scrollPane.setBounds(0, 0, width, height)

                        val margin = 16
                        val spacing = 8

                        val fabSize = directLinesFab.preferredSize

                        directLinesFab.setBounds(
                            width - fabSize.width - margin,
                            height - fabSize.height - margin,
                            fabSize.width,
                            fabSize.height,
                        )

                        orthogonalFab.setBounds(
                            width - fabSize.width - margin - spacing - fabSize.width,
                            height - fabSize.height - margin,
                            fabSize.width,
                            fabSize.height,
                        )

                        splineFab.setBounds(
                            width - fabSize.width - margin - (spacing + fabSize.width) * 2,
                            height - fabSize.height - margin,
                            fabSize.width,
                            fabSize.height,
                        )

                        cursorFollowFab.setBounds(
                            width - fabSize.width - margin - (spacing + fabSize.width) * 3,
                            height - fabSize.height - margin,
                            fabSize.width,
                            fabSize.height,
                        )

                        copyTreeFab.setBounds(
                            width - fabSize.width - margin - (spacing + fabSize.width) * 4,
                            height - fabSize.height - margin,
                            fabSize.width,
                            fabSize.height,
                        )

                        val pickerSize = edgeColorPicker.preferredSize

                        backgroundColorPicker.setBounds(
                            margin,
                            height - pickerSize.height - margin,
                            pickerSize.width,
                            pickerSize.height,
                        )

                        edgeColorPicker.setBounds(
                            margin + (pickerSize.width + spacing),
                            height - pickerSize.height - margin,
                            pickerSize.width,
                            pickerSize.height,
                        )

                        nodeColorPicker.setBounds(
                            margin + (pickerSize.width + spacing) * 2,
                            height - pickerSize.height - margin,
                            pickerSize.width,
                            pickerSize.height,
                        )

                        textColorPicker.setBounds(
                            margin + (pickerSize.width + spacing) * 3,
                            height - pickerSize.height - margin,
                            pickerSize.width,
                            pickerSize.height,
                        )

                        val resetSize = resetColorsButton.preferredSize
                        resetColorsButton.setBounds(
                            margin + (pickerSize.width + spacing) * 4,
                            height - resetSize.height - margin,
                            resetSize.width,
                            resetSize.height,
                        )

                        val lightSize = lightModeButton.preferredSize
                        lightModeButton.setBounds(
                            margin + (pickerSize.width + spacing) * 4 + resetSize.width + spacing,
                            height - lightSize.height - margin,
                            lightSize.width,
                            lightSize.height,
                        )
                    }

                    override fun getPreferredSize(): Dimension = scrollPane.preferredSize
                }
            containerPanel.add(scrollPane)
            containerPanel.add(copyTreeFab)
            containerPanel.add(cursorFollowFab)
            containerPanel.add(splineFab)
            containerPanel.add(orthogonalFab)
            containerPanel.add(directLinesFab)
            containerPanel.add(backgroundColorPicker)
            containerPanel.add(edgeColorPicker)
            containerPanel.add(nodeColorPicker)
            containerPanel.add(textColorPicker)
            containerPanel.add(resetColorsButton)
            containerPanel.add(lightModeButton)
            // Lower component indices paint above the scroll pane.
            containerPanel.setComponentZOrder(copyTreeFab, 0)
            containerPanel.setComponentZOrder(cursorFollowFab, 1)
            containerPanel.setComponentZOrder(splineFab, 2)
            containerPanel.setComponentZOrder(orthogonalFab, 3)
            containerPanel.setComponentZOrder(directLinesFab, 4)
            containerPanel.setComponentZOrder(backgroundColorPicker, 5)
            containerPanel.setComponentZOrder(edgeColorPicker, 6)
            containerPanel.setComponentZOrder(nodeColorPicker, 7)
            containerPanel.setComponentZOrder(textColorPicker, 8)
            containerPanel.setComponentZOrder(resetColorsButton, 9)
            containerPanel.setComponentZOrder(lightModeButton, 10)

            val treePanel = JPanel(BorderLayout(0, 0))
            treePanel.add(containerPanel, BorderLayout.CENTER)
            treePanel.add(scaleSlider, BorderLayout.SOUTH)

            val connection = project.messageBus.connect(parentDisposable)
            connection.subscribe(
                com.intellij.ide.ui.LafManagerListener.TOPIC,
                com.intellij.ide.ui.LafManagerListener {
                    val previewService = project.service<PreviewService>()
                    val settings = previewService.state.liveSettings

                    val bg = UIManager.getColor("Panel.background") ?: Color(60, 63, 65)
                    val light = (bg.red + bg.green + bg.blue) / 3 > 128

                    // Preserve user colors when the IDE theme changes.
                    if (settings?.treeBackgroundColor == null) {
                        uber.treeViewer.background = bg
                        backgroundColorPicker.currentColor = bg
                    }
                    if (settings?.treeTextColor == null) {
                        val c = if (light) Color.BLACK else Color.decode("#BCBEC4")
                        uber.treeViewer.textColor = c
                        textColorPicker.currentColor = c
                    }
                    if (settings?.treeNodeColor == null) {
                        val c = if (light) Color.decode("#606060") else Color.decode("#808080")
                        uber.treeViewer.nodeTextColor = c
                        nodeColorPicker.currentColor = c
                    }
                    if (settings?.treeEdgeColor == null) {
                        val c = if (light) Color(153, 153, 153) else Gray._96
                        uber.treeViewer.edgeColor = c
                        edgeColorPicker.currentColor = c
                    }

                    uber.treeViewer.repaint()
                },
            )

            return Pair(uber, treePanel)
        }
    }

    enum class IconType {
        SPLINE,
        ORTHOGONAL,
        DIRECT_LINES,
    }

    class FloatingActionButton(private val iconType: IconType = IconType.ORTHOGONAL) : JButton() {
        private val fabSize = 34

        companion object {
            private val SELECTION_COLOR_LIGHT = Color(0xA6, 0xD2, 0xFF)
            private val SELECTION_COLOR_DARK =
                Color(
                    (0xA6 * 0.4).toInt(),
                    (0xD2 * 0.4).toInt(),
                    (0xFF * 0.4).toInt(),
                )

            private val ICON_COLOR_LIGHT = Color(0x6C, 0x70, 0x7E)
            private val ICON_COLOR_DARK = Color(0xCE, 0xD0, 0xD6)

            fun getSelectionColor(): Color = if (JBColor.isBright()) SELECTION_COLOR_LIGHT else SELECTION_COLOR_DARK

            fun getIconColor(): Color = if (JBColor.isBright()) ICON_COLOR_LIGHT else ICON_COLOR_DARK
        }

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
                    getSelectionColor()
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

            g2.color = getIconColor()
            g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val centerX = (width - 4) / 2
            val centerY = (height - 4) / 2

            when (iconType) {
                IconType.SPLINE -> paintSplineIcon(g2, centerX, centerY)
                IconType.ORTHOGONAL -> paintOrthogonalIcon(g2, centerX, centerY)
                IconType.DIRECT_LINES -> paintDirectLinesIcon(g2, centerX, centerY)
            }

            g2.dispose()
        }

        private fun paintOrthogonalIcon(g2: Graphics2D, centerX: Int, centerY: Int) {
            val nodeRadius = 2
            g2.fillOval(
                centerX - nodeRadius,
                centerY - 8 - nodeRadius,
                nodeRadius * 2,
                nodeRadius * 2,
            )

            g2.drawLine(centerX, centerY - 6, centerX, centerY - 2)

            g2.drawLine(centerX - 8, centerY - 2, centerX + 8, centerY - 2)

            g2.drawLine(centerX - 8, centerY - 2, centerX - 8, centerY + 4)
            g2.drawLine(centerX, centerY - 2, centerX, centerY + 4)
            g2.drawLine(centerX + 8, centerY - 2, centerX + 8, centerY + 4)

            g2.fillOval(centerX - 8 - nodeRadius, centerY + 4, nodeRadius * 2, nodeRadius * 2)
            g2.fillOval(centerX - nodeRadius, centerY + 4, nodeRadius * 2, nodeRadius * 2)
            g2.fillOval(centerX + 8 - nodeRadius, centerY + 4, nodeRadius * 2, nodeRadius * 2)
        }

        private fun paintDirectLinesIcon(g2: Graphics2D, centerX: Int, centerY: Int) {
            val nodeRadius = 2

            g2.fillOval(
                centerX - nodeRadius,
                centerY - 8 - nodeRadius,
                nodeRadius * 2,
                nodeRadius * 2,
            )

            g2.drawLine(centerX, centerY - 6, centerX - 8, centerY + 4)
            g2.drawLine(centerX, centerY - 6, centerX, centerY + 4)
            g2.drawLine(centerX, centerY - 6, centerX + 8, centerY + 4)

            g2.fillOval(centerX - 8 - nodeRadius, centerY + 4, nodeRadius * 2, nodeRadius * 2)
            g2.fillOval(centerX - nodeRadius, centerY + 4, nodeRadius * 2, nodeRadius * 2)
            g2.fillOval(centerX + 8 - nodeRadius, centerY + 4, nodeRadius * 2, nodeRadius * 2)
        }

        private fun paintSplineIcon(g2: Graphics2D, centerX: Int, centerY: Int) {
            val nodeRadius = 2

            g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            g2.fillOval(
                centerX - nodeRadius,
                centerY - 9 - nodeRadius,
                nodeRadius * 2,
                nodeRadius * 2,
            )

            val rootY = (centerY - 7).toDouble()
            val childY = (centerY + 5).toDouble()
            val midY = (rootY + childY) / 2

            val leftCurve =
                CubicCurve2D.Double(
                    centerX.toDouble(),
                    rootY,
                    centerX.toDouble(),
                    midY + 2,
                    (centerX - 8).toDouble(),
                    midY - 2,
                    (centerX - 8).toDouble(),
                    childY,
                )
            g2.draw(leftCurve)

            g2.drawLine(centerX, rootY.toInt(), centerX, childY.toInt())

            val rightCurve =
                CubicCurve2D.Double(
                    centerX.toDouble(),
                    rootY,
                    centerX.toDouble(),
                    midY + 2,
                    (centerX + 8).toDouble(),
                    midY - 2,
                    (centerX + 8).toDouble(),
                    childY,
                )
            g2.draw(rightCurve)

            g2.fillOval(centerX - 8 - nodeRadius, centerY + 5, nodeRadius * 2, nodeRadius * 2)
            g2.fillOval(centerX - nodeRadius, centerY + 5, nodeRadius * 2, nodeRadius * 2)
            g2.fillOval(centerX + 8 - nodeRadius, centerY + 5, nodeRadius * 2, nodeRadius * 2)
        }

        override fun contains(x: Int, y: Int): Boolean = containsCircularPreviewPoint(x, y)
    }

    enum class ColorPickerType {
        EDGE,
        NODE, // Rule labels
        TEXT, // Terminal labels
        BACKGROUND,
    }

    class ColorPickerButton(
        private val pickerType: ColorPickerType,
        initialColor: Color,
        private val onColorChanged: (Color) -> Unit,
    ) : JButton() {
        private val buttonWidth = 28
        private val buttonHeight = 24
        private val cornerRadius = 5

        var currentColor: Color = initialColor
            set(value) {
                field = value
                repaint()
            }

        private val interaction = PreviewButtonInteraction(this)

        init {
            interaction.install(buttonWidth, buttonHeight) { showColorChooser() }
        }

        private fun showColorChooser() {
            val title =
                when (pickerType) {
                    ColorPickerType.EDGE -> "Choose Edge Color"
                    ColorPickerType.NODE -> "Choose Node Text Color"
                    ColorPickerType.TEXT -> "Choose Text Color"
                    ColorPickerType.BACKGROUND -> "Choose Background Color"
                }
            val chosen = JColorChooser.showDialog(this, title, currentColor)
            if (chosen != null) {
                currentColor = chosen
                onColorChanged(chosen)
            }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            g2.color = Color(0, 0, 0, 40)
            g2.fill(
                RoundRectangle2D.Double(
                    1.0,
                    2.0,
                    (width - 2).toDouble(),
                    (height - 2).toDouble(),
                    cornerRadius.toDouble(),
                    cornerRadius.toDouble(),
                ),
            )

            val swatchWidth = width - 12
            g2.color = currentColor
            g2.fill(
                RoundRectangle2D.Double(
                    0.0,
                    0.0,
                    swatchWidth.toDouble(),
                    (height - 2).toDouble(),
                    cornerRadius.toDouble(),
                    cornerRadius.toDouble(),
                ),
            )

            if (interaction.isHovered || interaction.isPressed) {
                val overlayAlpha = if (interaction.isPressed) 60 else 30
                g2.color = Color(255, 255, 255, overlayAlpha)
                g2.fill(
                    RoundRectangle2D.Double(
                        0.0,
                        0.0,
                        swatchWidth.toDouble(),
                        (height - 2).toDouble(),
                        cornerRadius.toDouble(),
                        cornerRadius.toDouble(),
                    ),
                )
            }

            g2.color = Color(100, 100, 100, 150)
            g2.stroke = BasicStroke(1f)
            g2.draw(
                RoundRectangle2D.Double(
                    0.0,
                    0.0,
                    swatchWidth.toDouble(),
                    (height - 2).toDouble(),
                    cornerRadius.toDouble(),
                    cornerRadius.toDouble(),
                ),
            )

            val iconX = swatchWidth + 2
            val iconCenterY = (height - 2) / 2
            g2.color = Color.WHITE
            g2.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            when (pickerType) {
                ColorPickerType.EDGE -> paintEdgeIcon(g2, iconX, iconCenterY)
                ColorPickerType.NODE -> paintNodeIcon(g2, iconX, iconCenterY)
                ColorPickerType.TEXT -> paintTextIcon(g2, iconX, iconCenterY)
                ColorPickerType.BACKGROUND -> paintBackgroundIcon(g2, iconX, iconCenterY)
            }

            g2.dispose()
        }

        private fun paintEdgeIcon(g2: Graphics2D, x: Int, centerY: Int) {
            val curve =
                QuadCurve2D.Double(
                    x.toDouble(),
                    (centerY - 4).toDouble(),
                    (x + 4).toDouble(),
                    centerY.toDouble(),
                    x.toDouble(),
                    (centerY + 4).toDouble(),
                )
            g2.draw(curve)
        }

        private fun paintNodeIcon(g2: Graphics2D, x: Int, centerY: Int) {
            g2.font = Font("SansSerif", Font.BOLD, 9)
            g2.drawString("N", x, centerY + 3)
        }

        private fun paintTextIcon(g2: Graphics2D, x: Int, centerY: Int) {
            g2.font = Font("SansSerif", Font.BOLD, 9)
            g2.drawString("A", x, centerY + 3)
        }

        private fun paintBackgroundIcon(g2: Graphics2D, x: Int, centerY: Int) {
            g2.fillRect(x, centerY - 4, 7, 8)
        }

        override fun contains(x: Int, y: Int): Boolean = x >= 0 && x < width && y >= 0 && y < height - 2
    }

    class ResetColorsButton(
        private val onReset: () -> Unit,
    ) : JButton() {
        private val buttonSize = 24

        private val interaction = PreviewButtonInteraction(this)

        init {
            interaction.install(buttonSize, buttonSize) { onReset() }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            g2.color = Color(0, 0, 0, 40)
            g2.fill(Ellipse2D.Double(2.0, 3.0, (width - 4).toDouble(), (height - 4).toDouble()))

            val baseBrightness = 0.02
            val stateBrightness =
                when {
                    interaction.isPressed -> 0.08
                    interaction.isHovered -> 0.04
                    else -> 0.0
                }
            g2.color = buttonColor(baseBrightness + stateBrightness)
            g2.fill(Ellipse2D.Double(0.0, 0.0, (width - 4).toDouble(), (height - 4).toDouble()))

            val centerX = (width - 4) / 2
            val centerY = (height - 4) / 2
            g2.color = FloatingActionButton.getIconColor()
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val arrowTipX = centerX - 5
            val arrowY = centerY - 3
            g2.drawLine(arrowTipX, arrowY, arrowTipX + 4, arrowY - 3)
            g2.drawLine(arrowTipX, arrowY, arrowTipX + 4, arrowY + 3)
            g2.drawLine(arrowTipX, arrowY, centerX + 3, arrowY)

            val arcSize = 8
            g2.draw(
                Arc2D.Double(
                    (centerX + 3 - arcSize / 2).toDouble(),
                    (arrowY).toDouble(),
                    arcSize.toDouble(),
                    arcSize.toDouble(),
                    90.0,
                    -180.0,
                    Arc2D.OPEN,
                ),
            )

            g2.drawLine(centerX + 3, arrowY + arcSize, centerX - 2, arrowY + arcSize)

            g2.dispose()
        }

        override fun contains(x: Int, y: Int): Boolean = containsCircularPreviewPoint(x, y)
    }

    class LightModeButton(
        private val onLightMode: () -> Unit,
    ) : JButton() {
        private val buttonSize = 24

        private val interaction = PreviewButtonInteraction(this)

        init {
            interaction.install(buttonSize, buttonSize) { onLightMode() }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            g2.color = Color(0, 0, 0, 40)
            g2.fill(Ellipse2D.Double(2.0, 3.0, (width - 4).toDouble(), (height - 4).toDouble()))

            val baseBrightness = 0.02
            val stateBrightness =
                when {
                    interaction.isPressed -> 0.08
                    interaction.isHovered -> 0.04
                    else -> 0.0
                }
            g2.color = buttonColor(baseBrightness + stateBrightness)
            g2.fill(Ellipse2D.Double(0.0, 0.0, (width - 4).toDouble(), (height - 4).toDouble()))

            val centerX = (width - 4) / 2
            val centerY = (height - 4) / 2
            g2.color = FloatingActionButton.getIconColor()
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val sunRadius = 3
            g2.fillOval(centerX - sunRadius, centerY - sunRadius, sunRadius * 2, sunRadius * 2)

            val innerRadius = 5
            val outerRadius = 7
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0)
                val x1 = centerX + (innerRadius * cos(angle)).toInt()
                val y1 = centerY - (innerRadius * sin(angle)).toInt()
                val x2 = centerX + (outerRadius * cos(angle)).toInt()
                val y2 = centerY - (outerRadius * sin(angle)).toInt()
                g2.drawLine(x1, y1, x2, y2)
            }

            g2.dispose()
        }

        override fun contains(x: Int, y: Int): Boolean = containsCircularPreviewPoint(x, y)
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
                    FloatingActionButton.getSelectionColor()
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

        override fun contains(x: Int, y: Int): Boolean = containsCircularPreviewPoint(x, y)
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
