import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReleaseArtifactsTest {
    @Test
    fun `collecting all variants retains every archive and its verifiable checksum`() {
        val root = Files.createTempDirectory("antlr4plus-release-").toFile()
        try {
            val destination = root.resolve("distributions")
            val variants =
                listOf(
                    "legacy" to "2026.1.12.1",
                    "modern" to "2026.1.12.2",
                    "newer" to "2026.1.12.3",
                )
            for ((variant, version) in variants) {
                val build = root.resolve("variants/$variant")
                val name = "antlr4plus-$version-$variant.zip"
                build.resolve("distributions").mkdirs()
                build.resolve("reports").mkdirs()
                build.resolve("distributions/$name").writeText("abc")
                build.resolve("reports/sbom.json").writeText("{}")
                ReleaseArtifacts.collect(build, destination, name)
                // A subsequent clean of this isolated child cannot remove the collected files.
                build.deleteRecursively()
            }
            assertEquals(12, destination.listFiles()!!.size)
            for ((variant, version) in variants) {
                val name = "antlr4plus-$version-$variant.zip"
                assertEquals("abc", destination.resolve(name).readText())
                assertEquals(
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  $name\n",
                    destination.resolve("$name.sha256").readText(),
                )
            }
            ReleaseArtifacts.verifyChecksums(destination)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing build outputs fail without overwriting an existing release`() {
        val root = Files.createTempDirectory("antlr4plus-release-missing-").toFile()
        try {
            val destination = root.resolve("distributions").apply { mkdirs() }
            val existing = destination.resolve("plugin.zip").apply { writeText("previous release") }
            val child = root.resolve("variant")
            assertFailsWith<IllegalArgumentException> {
                ReleaseArtifacts.collect(child, destination, "plugin.zip")
            }
            child.resolve("distributions").mkdirs()
            child.resolve("distributions/plugin.zip").writeText("replacement")
            assertFailsWith<IllegalArgumentException> {
                ReleaseArtifacts.collect(child, destination, "plugin.zip")
            }
            assertEquals("previous release", existing.readText())
            assertTrue(destination.listFiles()!!.size == 1)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `checksum verification rejects a changed release artifact`() {
        val root = Files.createTempDirectory("antlr4plus-release-tampered-").toFile()
        try {
            val build = root.resolve("variant")
            val destination = root.resolve("distributions")
            val name = "antlr4plus-2026.1.12.1-legacy.zip"
            build.resolve("distributions").mkdirs()
            build.resolve("reports").mkdirs()
            build.resolve("distributions/$name").writeText("original")
            build.resolve("reports/sbom.json").writeText("{}")
            ReleaseArtifacts.collect(build, destination, name)

            destination.resolve(name).writeText("changed")

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    ReleaseArtifacts.verifyChecksums(destination)
                }
            assertTrue(failure.message.orEmpty().contains("Checksum mismatch"))
        } finally {
            root.deleteRecursively()
        }
    }
}
