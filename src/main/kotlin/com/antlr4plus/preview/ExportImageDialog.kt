package com.antlr4plus.preview

import com.antlr4plus.compat.compatAddBrowseListener
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.*
import kotlinx.coroutines.*

/** Configures image export and precomputes raster bytes for size estimates and reuse when saving. */
class ExportImageDialog(
    private val project: Project,
    private val defaultDir: VirtualFile?,
    private val defaultFileName: String,
    private val renderPreview: ((scaleFactor: Double) -> BufferedImage)? = null,
) : DialogWrapper(project, true) {

    private val filePathField = TextFieldWithBrowseButton()
    private val formatComboBox = JComboBox<ImageFormat>()
    private val scaleLabel = JLabel("Scale:")
    private val scaleSlider = JSlider(50, 400, 100) // Percent scale: 0.5x to 4x.
    private val scaleValueLabel = JBLabel("1x")
    private val sizeLabel = JLabel("Size:")
    private val sizeSpinner = JBLabel(AnimatedIcon.Default())
    private val sizeValueLabel = JBLabel("")
    private val dimensionsLabel = JBLabel("")

    private var currentFormat: ImageFormat = ImageFormat.PNG

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var precomputeJob: Job? = null

    // Reuse the rendered image when only the encoding format changes.
    private var cachedRenderedImage: BufferedImage? = null
    private var cachedRenderScaleFactor: Double = -1.0

    private var precomputedBytes: ByteArray? = null
    private var precomputedFormat: ImageFormat? = null
    private var precomputedScaleFactor: Double = -1.0

    private val debounceMs = 200L
    private val jpegQuality = 90

    init {
        title = "Export Image"
        setOKButtonText("Export")
        init()
        setupComponents()
    }

    private fun setupComponents() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        descriptor.title = "Select Export Directory"
        filePathField.compatAddBrowseListener(
            "Select Export Location",
            "Choose where to save the exported image",
            project,
            descriptor,
        )

        val defaultPath =
            if (defaultDir != null) {
                "${defaultDir.path}/$defaultFileName.png"
            } else {
                "$defaultFileName.png"
            }
        filePathField.text = defaultPath

        val formats = ImageFormat.entries.toTypedArray()
        formats.forEach { formatComboBox.addItem(it) }
        formatComboBox.selectedItem = ImageFormat.PNG

        formatComboBox.addActionListener {
            val selected = formatComboBox.selectedItem as ImageFormat
            onFormatChanged(selected)
        }

        scaleSlider.majorTickSpacing = 50
        scaleSlider.minorTickSpacing = 25
        scaleSlider.snapToTicks = true
        scaleSlider.addChangeListener {
            val scale = getCurrentScaleFactor()
            scaleValueLabel.text =
                if (scale == scale.toInt().toDouble()) {
                    "${scale.toInt()}x"
                } else {
                    String.format("%.2fx", scale).replace(Regex("\\.?0+x$"), "x")
                }
            schedulePrecomputation()
        }

        onFormatChanged(ImageFormat.PNG)
    }

    private fun getCurrentScaleFactor(): Double = scaleSlider.value / 100.0

    private fun schedulePrecomputation() {
        if (renderPreview == null || currentFormat.isVector) {
            sizeSpinner.isVisible = false
            sizeValueLabel.text = ""
            dimensionsLabel.text = ""
            precomputedBytes = null
            return
        }

        precomputeJob?.cancel()

        val format = currentFormat
        val scaleFactor = getCurrentScaleFactor()

        if (precomputedBytes != null && precomputedFormat == format && precomputedScaleFactor == scaleFactor) {
            return
        }

        sizeSpinner.isVisible = true
        sizeValueLabel.text = ""

        precomputeJob = coroutineScope.launch {
            delay(debounceMs)

            try {
                val needsRender = cachedRenderedImage == null || cachedRenderScaleFactor != scaleFactor
                val image =
                    if (needsRender) {
                        withContext(Dispatchers.Default) {
                                renderPreview.invoke(scaleFactor)
                            }
                            .also {
                                cachedRenderedImage = it
                                cachedRenderScaleFactor = scaleFactor
                            }
                    } else {
                        cachedRenderedImage!!
                    }

                val bytes =
                    withContext(Dispatchers.Default) {
                        encodeImageToBytes(image, format, jpegQuality)
                    }

                precomputedBytes = bytes
                precomputedFormat = format
                precomputedScaleFactor = scaleFactor

                withContext(Dispatchers.Main) {
                    sizeSpinner.isVisible = false
                    sizeValueLabel.text = formatFileSize(bytes.size.toLong())
                    dimensionsLabel.text = "${image.width} × ${image.height} px"
                }
            } catch (e: CancellationException) {} catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    sizeSpinner.isVisible = false
                    sizeValueLabel.text = ""
                    dimensionsLabel.text = ""
                }
            }
        }
    }

    private fun formatFileSize(bytes: Long): String =
        when {
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }

    private fun onFormatChanged(format: ImageFormat) {
        currentFormat = format

        val currentPath = filePathField.text
        val lastDot = currentPath.lastIndexOf('.')
        val basePath = if (lastDot > 0) currentPath.substring(0, lastDot) else currentPath
        filePathField.text = "$basePath.${format.extension}"

        val enableScale = !format.isVector
        scaleLabel.isEnabled = enableScale
        scaleSlider.isEnabled = enableScale
        scaleValueLabel.isEnabled = enableScale
        sizeLabel.isEnabled = enableScale
        sizeValueLabel.isEnabled = enableScale
        dimensionsLabel.isEnabled = enableScale

        schedulePrecomputation()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = Insets(4, 8, 4, 8)
        gbc.anchor = GridBagConstraints.WEST

        var row = 0

        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel("Save to:"), gbc)
        gbc.gridx = 1
        gbc.gridwidth = 2
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(filePathField, gbc)
        gbc.gridwidth = 1
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel("Format:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.0
        formatComboBox.preferredSize = java.awt.Dimension(150, formatComboBox.preferredSize.height)
        panel.add(formatComboBox, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(scaleLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(scaleSlider, gbc)
        gbc.gridx = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        scaleValueLabel.preferredSize = java.awt.Dimension(45, scaleValueLabel.preferredSize.height)
        panel.add(scaleValueLabel, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(sizeLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.0
        val sizePanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(sizeSpinner)
                add(sizeValueLabel)
                add(Box.createHorizontalStrut(12))
                add(dimensionsLabel)
            }
        sizeSpinner.isVisible = false
        panel.add(sizePanel, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 4
        gbc.insets = Insets(12, 8, 4, 8)
        val helpText =
            JBLabel(
                "<html><i>SVG exports are infinitely zoomable. For raster formats, use higher scale factors for better quality when printing or zooming.</i></html>",
            )
        panel.add(helpText, gbc)

        panel.border = JBUI.Borders.empty(8)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val path = filePathField.text.trim()
        if (path.isEmpty()) {
            return ValidationInfo("Please specify a file path", filePathField)
        }

        val file = File(path)
        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) {
            return ValidationInfo("Directory does not exist: ${parentDir.path}", filePathField)
        }

        return null
    }

    /** Returns confirmed settings and any matching precomputed bytes, or null after cancellation. */
    fun getExportSettings(): ExportSettings? {
        if (!isOK) return null

        val scaleFactor = getCurrentScaleFactor()

        val precomputed =
            if (
                precomputedBytes != null && precomputedFormat == currentFormat && precomputedScaleFactor == scaleFactor
            ) {
                precomputedBytes
            } else {
                null
            }

        return ExportSettings(
            file = File(filePathField.text.trim()),
            format = currentFormat,
            scaleFactor = scaleFactor,
            jpegQuality = jpegQuality,
            precomputedData = precomputed,
        )
    }

    override fun dispose() {
        precomputeJob?.cancel()
        coroutineScope.cancel()
        cachedRenderedImage = null
        precomputedBytes = null
        super.dispose()
    }

    override fun getPreferredFocusedComponent(): JComponent = filePathField.textField
}
