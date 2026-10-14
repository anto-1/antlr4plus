package com.antlr4plus.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.ComposePanel
import com.antlr4plus.util.rethrowIfControlFlow
import com.intellij.openapi.diagnostic.Logger
import java.awt.BorderLayout
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/** Creates Jewel-themed Compose panels using the runtime selected by the distribution. */
object ComposeCompat {
    private val LOG = Logger.getInstance(ComposeCompat::class.java)

    val isComposeAvailable: Boolean by lazy {
        LOG.info("Checking Compose availability...")
        initializeCompose()
    }

    private fun initializeCompose(): Boolean {
        val ideProvides = isIdeComposeAvailable()
        if (ideProvides) {
            LOG.info("Using IDE-provided Compose - Skiko already initialized by IDE")
            return verifyComposeClasses()
        }

        LOG.info("Using fallback Compose JARs from lib/...")
        if (verifyComposeClasses()) {
            return tryInitializeSkiko()
        }

        LOG.warn("Compose classes not found in classpath")
        return false
    }

    private fun isIdeComposeAvailable(): Boolean {
        return try {
            val appInfo = com.intellij.openapi.application.ApplicationInfo.getInstance()
            val build = appInfo.build
            val productCode = build.productCode
            val baselineVersion = build.baselineVersion
            LOG.info(
                "IDE: ${appInfo.fullApplicationName}, build: ${build.asString()}, product: $productCode, baseline: $baselineVersion",
            )

            val isIdea = productCode == "IC" || productCode == "IU"
            if (isIdea && baselineVersion >= 253) {
                LOG.info("IDEA $baselineVersion detected - using IDE-provided Compose")
                return true
            }

            LOG.info("Using fallback Compose for $productCode $baselineVersion")
            false
        } catch (e: Throwable) {
            rethrowIfControlFlow(e)
            LOG.warn("Error checking IDE version: ${e.message}", e)
            false
        }
    }

    private fun verifyComposeClasses(): Boolean =
        try {
            val classLoader = ComposeCompat::class.java.classLoader
            classLoader.loadClass("androidx.compose.runtime.Composable")
            classLoader.loadClass("androidx.compose.ui.awt.ComposePanel")
            LOG.info("Compose classes verified available")
            true
        } catch (e: ClassNotFoundException) {
            LOG.warn("Compose classes not found: ${e.message}")
            false
        }

    /** Loads Skiko for distributions that bundle the fallback runtime. */
    private fun tryInitializeSkiko(): Boolean =
        try {
            org.jetbrains.skiko.Library.load()
            LOG.info("Skiko initialized successfully")
            true
        } catch (e: Throwable) {
            rethrowIfControlFlow(e)
            LOG.error("Failed to initialize Skiko", e)
            false
        }

    @OptIn(ExperimentalJewelApi::class)
    fun createComposePanel(content: @Composable () -> Unit): ComposePanel {
        LOG.info("Creating ComposePanel with SwingBridgeTheme")
        return try {
            ComposePanel().apply {
                layout = BorderLayout()
                setContent {
                    SwingBridgeTheme {
                        content()
                    }
                }
            }
        } catch (e: Throwable) {
            rethrowIfControlFlow(e)
            LOG.error("Failed to create ComposePanel", e)
            throw e
        }
    }

    /** Untyped panel references keep concrete Compose panels out of the tool-window signature. */
    data class ComposePanels(
        val profilerPanel: Any, // ComposeProfilerPanel
        val profilerComponent: javax.swing.JComponent,
        val timelinePanel: Any, // ComposeTokenTimelinePanel
        val timelineComponent: javax.swing.JComponent,
        val settingsPanel: javax.swing.JPanel,
    )

    /** Instantiate after the caller has checked [isComposeAvailable]. */
    fun createComposePanels(
        project: com.intellij.openapi.project.Project,
        parentDisposable: com.intellij.openapi.Disposable = project,
    ): ComposePanels {
        LOG.info("Creating Compose panels for tool window")

        val profilerPanel = com.antlr4plus.statistics.ComposeProfilerPanel(project, parentDisposable)
        val timelinePanel = com.antlr4plus.timeline.ComposeTokenTimelinePanel(project)

        val settingsPanel =
            javax.swing.JPanel(BorderLayout()).apply {
                add(
                    createComposePanel {
                        com.antlr4plus.settings.SettingsPanel(project)
                    },
                )
            }

        return ComposePanels(
            profilerPanel = profilerPanel,
            profilerComponent = profilerPanel.getComponent(),
            timelinePanel = timelinePanel,
            timelineComponent = timelinePanel.getComponent(),
            settingsPanel = settingsPanel,
        )
    }
}
