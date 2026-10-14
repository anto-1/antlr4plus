package com.antlr4plus.statistics

import com.antlr4plus.settings.LiveSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*
import javax.swing.event.ChangeListener

/** Configures persisted benchmark timings for one preview input. */
class BenchmarkSettingsDialog(
    private val settings: LiveSettings,
    private val onApply: (() -> Unit)? = null,
) : DialogWrapper(true) {

    private val warmupSpinner =
        JSpinner(
            SpinnerNumberModel(
                settings.benchmarkWarmupDurationMs.toInt(),
                100,
                60000,
                100,
            ),
        )
    private val measurementSpinner =
        JSpinner(
            SpinnerNumberModel(
                settings.benchmarkMeasurementDurationMs.toInt(),
                100,
                60000,
                100,
            ),
        )
    private val parsingTimeoutSpinner =
        JSpinner(
            SpinnerNumberModel(
                settings.parsingTimeoutMs.toInt(),
                200,
                120000,
                100,
            ),
        )

    init {
        title = "Benchmark Settings"
        setOKButtonText("Apply")
        init()
        setupConstraints()
    }

    private fun setupConstraints() {
        val constraintListener = ChangeListener {
            val warmup = (warmupSpinner.value as Number).toInt()
            val measurement = (measurementSpinner.value as Number).toInt()
            val timeout = (parsingTimeoutSpinner.value as Number).toInt()
            val minRequired = warmup + measurement

            if (timeout < minRequired) {
                parsingTimeoutSpinner.value = minRequired
            }
        }
        warmupSpinner.addChangeListener(constraintListener)
        measurementSpinner.addChangeListener(constraintListener)

        parsingTimeoutSpinner.addChangeListener {
            val timeout = (parsingTimeoutSpinner.value as Number).toInt()
            val warmup = (warmupSpinner.value as Number).toInt()
            val measurement = (measurementSpinner.value as Number).toInt()

            if (warmup + measurement > timeout) {
                val newMeasurement = (timeout - warmup).coerceAtLeast(100)
                measurementSpinner.value = newMeasurement
            }
        }
    }

    private fun createHelpButton(helpText: String): JButton =
        JButton(AllIcons.General.ContextHelp).also { button ->
            button.isBorderPainted = false
            button.isContentAreaFilled = false
            button.preferredSize = java.awt.Dimension(20, 20)
            button.toolTipText = helpText
            button.addActionListener {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    helpText.replace("<br>", "\n").replace(Regex("<[^>]*>"), ""),
                    "Help",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }
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
        panel.add(JLabel("Warmup duration:"), gbc)
        gbc.gridx = 1
        panel.add(warmupSpinner, gbc)
        gbc.gridx = 2
        panel.add(JLabel("ms"), gbc)
        gbc.gridx = 3
        panel.add(
            createHelpButton(
                "<html><b>JVM Warmup Duration</b><br><br>" +
                    "Time spent running the parser before measurement begins.<br>" +
                    "During warmup, the JIT compiler optimizes frequently-executed code paths.<br><br>" +
                    "<b>Why it matters:</b><br>" +
                    "Cold JVM code runs 5-10x slower than optimized code. Without adequate warmup,<br>" +
                    "your benchmark measures JIT compilation time, not actual parser performance.<br><br>" +
                    "<b>Recommended values:</b><br>" +
                    "• 500ms - Quick feedback during development<br>" +
                    "• 1000-2000ms - Good balance for accurate results<br>" +
                    "• 3000-5000ms - Thorough warmup for publication-quality benchmarks</html>",
            ),
            gbc,
        )
        row++

        gbc.gridx = 0
        gbc.gridy = row
        panel.add(JLabel("Measurement duration:"), gbc)
        gbc.gridx = 1
        panel.add(measurementSpinner, gbc)
        gbc.gridx = 2
        panel.add(JLabel("ms"), gbc)
        gbc.gridx = 3
        panel.add(
            createHelpButton(
                "<html><b>Measurement Duration</b><br><br>" +
                    "Time spent collecting timing data after warmup completes.<br>" +
                    "More time = more iterations = better statistical accuracy.<br><br>" +
                    "<b>Why it matters:</b><br>" +
                    "Short measurements have high variance due to OS scheduling, GC pauses,<br>" +
                    "and other noise. Longer measurements average out these effects.<br><br>" +
                    "<b>Recommended values:</b><br>" +
                    "• 500ms - Quick estimate, expect ±10-20% variance<br>" +
                    "• 1000-2000ms - Good accuracy, ±5-10% variance<br>" +
                    "• 5000ms+ - High precision for detailed analysis</html>",
            ),
            gbc,
        )
        row++

        gbc.gridx = 0
        gbc.gridy = row
        panel.add(JLabel("Parsing timeout:"), gbc)
        gbc.gridx = 1
        panel.add(parsingTimeoutSpinner, gbc)
        gbc.gridx = 2
        panel.add(JLabel("ms"), gbc)
        gbc.gridx = 3
        panel.add(
            createHelpButton(
                "<html><b>Parsing Timeout</b><br><br>" +
                    "Maximum time allowed for the entire benchmark (warmup + measurement).<br>" +
                    "Must be ≥ Warmup + Measurement duration.<br><br>" +
                    "<b>Why it matters:</b><br>" +
                    "Prevents runaway benchmarks when parsing slow grammars or large inputs.<br>" +
                    "The benchmark will stop when this limit is reached.<br><br>" +
                    "<b>Recommended values:</b><br>" +
                    "• 2000ms - Quick iteration during development<br>" +
                    "• 5000ms - Balanced for most workflows<br>" +
                    "• 15000ms+ - Thorough analysis when accuracy is critical<br><br>" +
                    "<i>This value auto-adjusts to stay ≥ warmup + measurement.</i></html>",
            ),
            gbc,
        )
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 4
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(8, 8, 8, 8)
        panel.add(JSeparator(), gbc)
        gbc.gridwidth = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.insets = Insets(4, 8, 4, 8)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 4
        val resetButton = JButton("Reset to Defaults")
        resetButton.addActionListener { resetToDefaults() }
        panel.add(resetButton, gbc)
        row++

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 4
        gbc.insets = Insets(12, 8, 4, 8)
        val helpText =
            JBLabel(
                "<html><i>For JMH-quality results, use longer warmup and measurement times.<br>Click the help buttons for detailed guidance.</i></html>",
            )
        panel.add(helpText, gbc)

        panel.border = JBUI.Borders.empty(8)
        return panel
    }

    private fun resetToDefaults() {
        parsingTimeoutSpinner.value = 2000 // Set the deadline first to avoid duration-listener adjustments.
        warmupSpinner.value = 500
        measurementSpinner.value = 1000
    }

    override fun doValidate(): ValidationInfo? {
        val warmup = (warmupSpinner.value as Number).toInt()
        val measurement = (measurementSpinner.value as Number).toInt()
        val timeout = (parsingTimeoutSpinner.value as Number).toInt()

        if (warmup + measurement > timeout) {
            return ValidationInfo(
                "Warmup ($warmup) + Measurement ($measurement) = ${warmup + measurement}ms exceeds Parsing Timeout ($timeout ms)",
                parsingTimeoutSpinner,
            )
        }
        return null
    }

    override fun doOKAction() {
        settings.benchmarkWarmupDurationMs = (warmupSpinner.value as Number).toLong()
        settings.benchmarkMeasurementDurationMs = (measurementSpinner.value as Number).toLong()
        settings.parsingTimeoutMs = (parsingTimeoutSpinner.value as Number).toLong()
        super.doOKAction()
        onApply?.invoke()
    }
}
