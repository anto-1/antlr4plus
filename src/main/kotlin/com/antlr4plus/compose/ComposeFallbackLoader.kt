package com.antlr4plus.compose

import com.antlr4plus.util.rethrowIfControlFlow
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger

/** Checks bundled Compose classes when the IDE is not expected to provide the runtime. */
object ComposeFallbackLoader {
    private val log = Logger.getInstance(ComposeFallbackLoader::class.java)

    @Volatile private var initialized = false

    @Volatile private var available = false

    @Synchronized
    fun ensureComposeAvailable(): Boolean {
        if (initialized) return available
        initialized = true

        val ide = ideInfo()
        available =
            if (ide.providesCompose) {
                log.info("Using Compose provided by ${ide.productCode} ${ide.baselineVersion}")
                true
            } else {
                verifyBundledCompose()
            }
        return available
    }

    private data class IdeInfo(
        val productCode: String,
        val baselineVersion: Int,
        val providesCompose: Boolean,
    )

    private fun ideInfo(): IdeInfo =
        try {
            val build = ApplicationInfo.getInstance().build
            val productCode = build.productCode
            val providesCompose = productCode in setOf("IC", "IU") && build.baselineVersion >= 253
            IdeInfo(productCode, build.baselineVersion, providesCompose)
        } catch (failure: Throwable) {
            rethrowIfControlFlow(failure)
            log.warn("Could not determine IDE Compose availability", failure)
            IdeInfo("unknown", 0, false)
        }

    private fun verifyBundledCompose(): Boolean =
        try {
            val classLoader = ComposeFallbackLoader::class.java.classLoader
            classLoader.loadClass("androidx.compose.runtime.Composable")
            classLoader.loadClass("androidx.compose.ui.awt.ComposePanel")
            log.info("Using Compose bundled with the plugin")
            true
        } catch (failure: ClassNotFoundException) {
            log.error("Plugin package is missing its Compose runtime", failure)
            false
        }
}
