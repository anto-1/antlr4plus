package com.antlr4plus.preview

import com.antlr4plus.preview.tree.TreeViewer
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.util.SimpleSVGGraphics2D
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.guessProjectDir
import com.intellij.ui.JBColor
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

internal object ParseTreeContextualMenu {
    fun showPopupMenu(parseTreeViewer: TreeViewer, event: MouseEvent) {
        val menu = JPopupMenu()

        menu.add(createExportMenuItem(parseTreeViewer, "Export full tree..."))
        menu.add(
            createExportMenuItem(
                parseTreeViewer,
                "Export full tree (transparent)...",
                useTransparentBackground = true,
            ),
        )
        menu.addSeparator()
        menu.add(
            createExportMenuItem(parseTreeViewer, "Export viewport only...", viewportOnly = true),
        )
        menu.add(
            createExportMenuItem(
                parseTreeViewer,
                "Export viewport only (transparent)...",
                useTransparentBackground = true,
                viewportOnly = true,
            ),
        )

        menu.show(parseTreeViewer, event.getX(), event.getY())
    }

    private fun createExportMenuItem(
        parseTreeViewer: TreeViewer,
        label: String,
        useTransparentBackground: Boolean = false,
        viewportOnly: Boolean = false,
    ): JMenuItem {
        val item = JMenuItem(label)

        item.addActionListener { _: ActionEvent? ->
            val project = parseTreeViewer.viewer.project
            val previewService = project.getService(PreviewService::class.java)
            val g4pFile = previewService.state.g4pInputFile
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val baseName = g4pFile?.nameWithoutExtension ?: "parseTree"
            val suffix = if (viewportOnly) "-Viewport" else "-Tree"
            val defaultFileName = "$baseName$suffix-$timestamp"

            val baseDir = g4pFile?.parent ?: project.guessProjectDir()

            val renderPreview: (Double) -> BufferedImage = { scaleFactor ->
                renderTreeToImage(
                    parseTreeViewer,
                    useTransparentBackground,
                    viewportOnly,
                    scaleFactor,
                )
            }

            val dialog =
                ExportImageDialog(
                    project = project,
                    defaultDir = baseDir,
                    defaultFileName = defaultFileName,
                    renderPreview = renderPreview,
                )

            if (!dialog.showAndGet()) {
                return@addActionListener
            }

            val settings = dialog.getExportSettings() ?: return@addActionListener

            when {
                settings.precomputedData != null -> {
                    try {
                        settings.file.writeBytes(settings.precomputedData)
                    } catch (e: Exception) {
                        Logger.getInstance(ParseTreeContextualMenu::class.java)
                            .error(
                                "Error writing precomputed image to ${settings.file.absolutePath}",
                                e,
                            )
                    }
                }
                settings.format == ImageFormat.SVG -> {
                    exportToSvg(
                        parseTreeViewer,
                        settings.file,
                        useTransparentBackground,
                        viewportOnly,
                    )
                }
                settings.format == ImageFormat.JPEG -> {
                    exportToImage(
                        parseTreeViewer,
                        settings.file,
                        false,
                        "jpg",
                        viewportOnly,
                        settings.scaleFactor,
                        settings.jpegQuality,
                    )
                }
                else -> {
                    exportToImage(
                        parseTreeViewer,
                        settings.file,
                        useTransparentBackground,
                        settings.format.extension,
                        viewportOnly,
                        settings.scaleFactor,
                    )
                }
            }
        }

        return item
    }

    private fun exportToImage(
        parseTreeViewer: TreeViewer,
        file: File,
        useTransparentBackground: Boolean,
        imageFormat: String,
        viewportOnly: Boolean,
        scaleFactor: Double = 1.0,
        jpegQuality: Int = 90,
    ) {
        val viewportBounds = if (viewportOnly) parseTreeViewer.getViewportBounds() else null

        val size =
            parseTreeViewer.getExportBaseSize(viewportBounds)
                ?: run {
                    Logger.getInstance(ParseTreeContextualMenu::class.java)
                        .warn("Cannot export: tree layout has no valid bounds")
                    return
                }
        val baseWidth: Int = size.width
        val baseHeight: Int = size.height

        if (baseWidth <= 0 || baseHeight <= 0) {
            Logger.getInstance(ParseTreeContextualMenu::class.java)
                .warn("Cannot export: invalid image dimensions ($baseWidth x $baseHeight)")
            return
        }

        val width = (baseWidth * scaleFactor).toInt()
        val height = (baseHeight * scaleFactor).toInt()

        val bi =
            renderTreeImage(
                parseTreeViewer,
                width,
                height,
                useTransparentBackground,
                viewportBounds,
                scaleFactor,
            )

        try {
            if (imageFormat == "jpg" || imageFormat == "jpeg") {
                if (!writeJpegIfAvailable(bi, file, jpegQuality)) {
                    ImageIO.write(bi, imageFormat, file)
                }
            } else {
                if (!ImageIO.write(bi, imageFormat, file)) {
                    val notification =
                        Notification(
                            "ANTLR 4 export",
                            "Error while exporting parse tree to file " + file.absolutePath,
                            "unknown format '$imageFormat'?",
                            NotificationType.WARNING,
                        )
                    Notifications.Bus.notify(notification)
                }
            }
        } catch (e: IOException) {
            Logger.getInstance(ParseTreeContextualMenu::class.java)
                .error("Error while exporting parse tree to file " + file.absolutePath, e)
        }
    }

    private fun renderTreeToImage(
        parseTreeViewer: TreeViewer,
        useTransparentBackground: Boolean,
        viewportOnly: Boolean,
        scaleFactor: Double,
    ): BufferedImage {
        val viewportBounds = if (viewportOnly) parseTreeViewer.getViewportBounds() else null

        val size =
            parseTreeViewer.getExportBaseSize(viewportBounds) ?: return BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val baseWidth: Int = size.width
        val baseHeight: Int = size.height

        if (baseWidth <= 0 || baseHeight <= 0) {
            return BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        }

        val width = (baseWidth * scaleFactor).toInt()
        val height = (baseHeight * scaleFactor).toInt()

        return renderTreeImage(
            parseTreeViewer,
            width,
            height,
            useTransparentBackground,
            viewportBounds,
            scaleFactor,
        )
    }

    private fun exportToSvg(
        parseTreeViewer: TreeViewer,
        file: File,
        useTransparentBackground: Boolean,
        viewportOnly: Boolean,
    ) {
        val viewportBounds = if (viewportOnly) parseTreeViewer.getViewportBounds() else null

        val size =
            parseTreeViewer.getExportBaseSize(viewportBounds)
                ?: run {
                    Logger.getInstance(ParseTreeContextualMenu::class.java)
                        .warn("Cannot export SVG: tree layout has no valid bounds")
                    return
                }
        val width: Int = size.width
        val height: Int = size.height

        if (width <= 0 || height <= 0) {
            Logger.getInstance(ParseTreeContextualMenu::class.java)
                .warn("Cannot export SVG: invalid dimensions ($width x $height)")
            return
        }

        val svgGenerator = SimpleSVGGraphics2D(width.toDouble(), height.toDouble())
        svgGenerator.setClip(0, 0, width, height)

        if (!useTransparentBackground) {
            svgGenerator.color = JBColor.WHITE
            svgGenerator.fillRect(0, 0, width, height)
        }

        if (viewportOnly && viewportBounds != null) {
            svgGenerator.translate(-viewportBounds.x, -viewportBounds.y)
        }

        parseTreeViewer.paintForExport(svgGenerator, fillBackground = false)

        try {
            BufferedWriter(FileWriter(file)).use { writer ->
                writer.write(svgGenerator.svgDocument)
            }
        } catch (e: IOException) {
            Logger.getInstance(ParseTreeContextualMenu::class.java)
                .error("Error while exporting parse tree to SVG file " + file.absolutePath, e)
        }
    }
}
