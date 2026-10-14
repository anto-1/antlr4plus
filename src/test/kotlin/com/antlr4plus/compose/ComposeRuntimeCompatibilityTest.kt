package com.antlr4plus.compose

import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.paragraph.FontRastrSettings
import org.jetbrains.skia.paragraph.ParagraphStyle

/** Checks the JVM/native Skiko boundary for binary compatibility. */
class ComposeRuntimeCompatibilityTest : BasePlatformTestCase() {

    fun `test IDE Compose and Skiko paragraph native API are binary compatible`() {
        assertTrue(ComposeCompat.isComposeAvailable)

        val previousLibraryPath = System.getProperty(SKIKO_LIBRARY_PATH_PROPERTY)
        try {
            // The test bootstrap needs the native-library path normally supplied by IDE startup.
            findIdeSkikoNativeDirectory()?.let { directory ->
                System.setProperty(SKIKO_LIBRARY_PATH_PROPERTY, directory.toString())
            }

            ParagraphStyle().use { style ->
                val settings =
                    FontRastrSettings(
                        FontEdging.ANTI_ALIAS,
                        FontHinting.NORMAL,
                        true,
                    )
                style.fontRastrSettings = settings
                assertEquals(settings, style.fontRastrSettings)
            }
        } finally {
            if (previousLibraryPath == null) {
                System.clearProperty(SKIKO_LIBRARY_PATH_PROPERTY)
            } else {
                System.setProperty(SKIKO_LIBRARY_PATH_PROPERTY, previousLibraryPath)
            }
        }
    }

    private fun findIdeSkikoNativeDirectory(): Path? {
        val directory = Path.of(PathManager.getHomePath(), "lib", "skiko-awt-runtime-all")
        return directory.takeIf(Files::isDirectory)
    }

    companion object {
        private const val SKIKO_LIBRARY_PATH_PROPERTY = "skiko.library.path"
    }
}
