import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Compose runtime selection and distribution checks shared by build tasks. */
object ComposePackaging {
    fun desktopArtifact(
        version: String,
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): String {
        val platform =
            when {
                osName.contains("linux", ignoreCase = true) -> "linux"
                osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true) -> "macos"
                osName.contains("windows", ignoreCase = true) -> "windows"
                else -> "linux"
            }
        val architecture =
            when {
                osArch.contains("aarch64", ignoreCase = true) || osArch.contains("arm64", ignoreCase = true) -> "arm64"
                else -> "x64"
            }
        return "org.jetbrains.compose.desktop:desktop-jvm-$platform-$architecture:$version"
    }

    fun isIdeProvidedRuntime(name: String): Boolean =
        IDE_RUNTIME_PREFIXES.any(name::startsWith) || (name.startsWith("ui-") && name.contains("-desktop-"))

    fun verifyIdeProvidedArchive(archive: File) {
        val (conflictingEntries, pluginDescriptors) = inspectArchive(archive)
        check(conflictingEntries.isEmpty()) {
            "IDE-provided Compose build contains fallback runtime JARs: " + conflictingEntries.sorted().joinToString()
        }
        val pluginDescriptor =
            pluginDescriptors.singleOrNull()
                ?: error(
                    "Expected exactly one embedded plugin descriptor in ${archive.name}, " +
                        "found ${pluginDescriptors.size}",
                )
        check(REQUIRED_COMPOSE_DEPENDENCY in pluginDescriptor) {
            "IDE-provided Compose build does not require com.intellij.modules.compose"
        }
    }

    private fun inspectArchive(archive: File): Pair<List<String>, List<String>> =
        ZipFile(archive).use { zip ->
            val conflicts =
                zip.entries()
                    .asSequence()
                    .map { it.name }
                    .filter { entryPath ->
                        val name = entryPath.substringAfterLast('/')
                        name.endsWith(".jar") &&
                            (isIdeProvidedRuntime(name) ||
                                name.startsWith("kotlin-stdlib") ||
                                name.startsWith("kotlinx-coroutines")) &&
                            "/kotlin-compiler/" !in entryPath
                    }
                    .map { it.substringAfterLast('/') }
                    .toList()

            val descriptors =
                zip.entries()
                    .asSequence()
                    .filter { entry -> entry.name.contains("/lib/") && entry.name.endsWith(".jar") }
                    .mapNotNull { pluginJarEntry ->
                        ZipInputStream(zip.getInputStream(pluginJarEntry)).use { pluginJar ->
                            generateSequence { pluginJar.nextEntry }
                                .firstOrNull { it.name == "META-INF/plugin.xml" }
                                ?.let { pluginJar.readBytes().toString(Charsets.UTF_8) }
                        }
                    }
                    .toList()

            conflicts to descriptors
        }

    private val IDE_RUNTIME_PREFIXES =
        listOf(
            "skiko-",
            "jewel-",
            "desktop-jvm-",
            "foundation-desktop-",
            "foundation-layout-desktop-",
            "animation-desktop-",
            "animation-core-desktop-",
            "runtime-desktop-",
            "runtime-saveable-desktop-",
            "lifecycle-",
            "core-common-",
            "collection-jvm-",
            "annotation-jvm-",
        )

    private const val REQUIRED_COMPOSE_DEPENDENCY = "<depends>com.intellij.modules.compose</depends>"
}
