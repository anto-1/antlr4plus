import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposePackagingTest {
    @Test
    fun `desktop artifact follows operating system and architecture`() {
        assertEquals(
            "org.jetbrains.compose.desktop:desktop-jvm-linux-x64:1.2.3",
            ComposePackaging.desktopArtifact("1.2.3", "Linux", "amd64"),
        )
        assertEquals(
            "org.jetbrains.compose.desktop:desktop-jvm-macos-arm64:1.2.3",
            ComposePackaging.desktopArtifact("1.2.3", "Mac OS X", "aarch64"),
        )
        assertEquals(
            "org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.2.3",
            ComposePackaging.desktopArtifact("1.2.3", "Windows 11", "x86_64"),
        )
    }

    @Test
    fun `IDE runtime classifier covers Compose and Skiko without matching plugin jars`() {
        assertTrue(ComposePackaging.isIdeProvidedRuntime("skiko-awt-runtime-linux-x64.jar"))
        assertTrue(ComposePackaging.isIdeProvidedRuntime("ui-desktop-1.8.0.jar"))
        assertTrue(ComposePackaging.isIdeProvidedRuntime("jewel-ide-laf-bridge-243.jar"))
        assertFalse(ComposePackaging.isIdeProvidedRuntime("antlr4plus-2026.1.12.jar"))
        assertFalse(ComposePackaging.isIdeProvidedRuntime("kotlin-compiler-embeddable.jar"))
    }
}
