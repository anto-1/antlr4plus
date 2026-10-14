import groovy.json.JsonSlurper
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

/** Verifies one installable plugin ZIP and its CycloneDX inventory. */
abstract class VerifyDistributionTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archiveFile: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val sbomFile: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val projectLicenseFile: RegularFileProperty

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val licensesDirectory: DirectoryProperty

    @get:Input abstract val variantName: Property<String>

    @get:Input abstract val composeFallbackBundled: Property<Boolean>

    @TaskAction
    fun verifyDistribution() {
        try {
            val components =
                DistributionVerifier.verify(
                    archive = archiveFile.get().asFile,
                    sbom = sbomFile.get().asFile,
                    projectLicense = projectLicenseFile.get().asFile,
                    licensesDirectory = licensesDirectory.get().asFile,
                    variant = BuildVariants.byName(variantName.get()),
                    bundleComposeFallback = composeFallbackBundled.get(),
                )
            logger.lifecycle(
                "Verified ${archiveFile.get().asFile.name}: licenses, runtimes, IDE bounds, " +
                    "and SBOM ($components components)",
            )
        } catch (failure: Exception) {
            throw GradleException("Distribution verification failed: ${failure.message}", failure)
        }
    }
}

object DistributionVerifier {
    private val compilerJarPrefixes =
        listOf(
            "kotlin-compiler-embeddable-",
            "kotlin-stdlib-",
            "kotlin-reflect-",
            "kotlinx-coroutines-core-jvm-",
            "annotations-",
        )

    private val requiredLicenseFiles =
        listOf(
            "licenses/ABEGO-TREELAYOUT-LICENSE.txt",
            "licenses/ANTLR-INTELLIJ-GRAMMAR-NOTICES.txt",
            "licenses/ANTLR-INTELLIJ-LICENSE.txt",
            "licenses/ANTLR-SOURCE-NOTICES.txt",
            "licenses/ANTLR-KOTLIN-LICENSE-BSD.txt",
            "licenses/ANTLR3-LICENSE.txt",
            "licenses/ANTLR4-INTELLIJ-ADAPTOR-LICENSE.txt",
            "licenses/ANTLR4-LICENSE.txt",
            "licenses/ANTLR4-RUST-TARGET-LICENSE.txt",
            "licenses/DEVICON-LICENSE.txt",
            "licenses/EPL-2.0.txt",
            "licenses/IMAGEMAGICK-LICENSE.txt",
            "licenses/KOTLIN-NOTICE.txt",
            "licenses/KOTLINX-COROUTINES-NOTICE.txt",
            "licenses/SKIA-LICENSE.txt",
            "licenses/SKIKO-NOTICE.txt",
            "licenses/STRINGTEMPLATE4-LICENSE.txt",
            "licenses/TWELVEMONKEYS-LICENSE.txt",
            "licenses/TWELVEMONKEYS-THIRD-PARTY-NOTICES.txt",
            "licenses/kotlin/LICENSE.txt",
            "licenses/kotlin/NOTICE.txt",
            "licenses/skia/m116-47d3027-1/SOURCES.txt",
            "licenses/skia/m132-9b3c42e2f9-3/SOURCES.txt",
        )

    private val skiaVersions = listOf("m116-47d3027-1", "m132-9b3c42e2f9-3")

    private val skiaLicensePaths =
        listOf(
            "SOURCES.txt",
            "include/third_party/vulkan/LICENSE",
            "third_party/expat/LICENSE",
            "third_party/externals/angle2/LICENSE",
            "third_party/externals/dng_sdk/LICENSE",
            "third_party/externals/dng_sdk/LICENSE.source_code",
            "third_party/externals/dng_sdk/LICENSE.technology",
            "third_party/externals/expat/COPYING",
            "third_party/externals/freetype/LICENSE.TXT",
            "third_party/externals/freetype/docs/FTL.TXT",
            "third_party/externals/freetype/docs/GPLv2.TXT",
            "third_party/externals/harfbuzz/COPYING",
            "third_party/externals/icu/LICENSE",
            "third_party/externals/icu/license.html",
            "third_party/externals/libjpeg-turbo/LICENSE.md",
            "third_party/externals/libjpeg-turbo/README.ijg",
            "third_party/externals/libpng/LICENSE",
            "third_party/externals/libwebp/COPYING",
            "third_party/externals/piex/LICENSE",
            "third_party/externals/swiftshader/LICENSE.txt",
            "third_party/externals/wuffs/LICENSE",
            "third_party/externals/zlib/LICENSE",
            "third_party/wuffs/LICENSE",
        )

    private val nonApacheLicenseCoverage: Map<Pair<String?, String?>, List<String>> =
        mapOf(
            ("com.strumenta" to "antlr-kotlin-runtime") to
                listOf(
                    "licenses/ANTLR-KOTLIN-LICENSE-BSD.txt",
                    "licenses/ANTLR-SOURCE-NOTICES.txt",
                ),
            ("com.strumenta" to "antlr-kotlin-runtime-jvm") to
                listOf(
                    "licenses/ANTLR-KOTLIN-LICENSE-BSD.txt",
                    "licenses/ANTLR-SOURCE-NOTICES.txt",
                ),
            ("com.strumenta" to "antlr-kotlin-target") to
                listOf(
                    "licenses/ANTLR-KOTLIN-LICENSE-BSD.txt",
                    "licenses/ANTLR-SOURCE-NOTICES.txt",
                ),
            ("com.twelvemonkeys.common" to "common-image") to
                listOf(
                    "licenses/TWELVEMONKEYS-LICENSE.txt",
                    "licenses/TWELVEMONKEYS-THIRD-PARTY-NOTICES.txt",
                    "licenses/IMAGEMAGICK-LICENSE.txt",
                ),
            ("com.twelvemonkeys.common" to "common-io") to
                listOf(
                    "licenses/TWELVEMONKEYS-LICENSE.txt",
                    "licenses/TWELVEMONKEYS-THIRD-PARTY-NOTICES.txt",
                ),
            ("com.twelvemonkeys.common" to "common-lang") to
                listOf(
                    "licenses/TWELVEMONKEYS-LICENSE.txt",
                    "licenses/TWELVEMONKEYS-THIRD-PARTY-NOTICES.txt",
                ),
            ("com.twelvemonkeys.imageio" to "imageio-core") to
                listOf(
                    "licenses/TWELVEMONKEYS-LICENSE.txt",
                    "licenses/TWELVEMONKEYS-THIRD-PARTY-NOTICES.txt",
                ),
            ("com.twelvemonkeys.imageio" to "imageio-metadata") to
                listOf(
                    "licenses/TWELVEMONKEYS-LICENSE.txt",
                    "licenses/TWELVEMONKEYS-THIRD-PARTY-NOTICES.txt",
                    "licenses/IMAGEMAGICK-LICENSE.txt",
                ),
            ("com.twelvemonkeys.imageio" to "imageio-tiff") to
                listOf(
                    "licenses/TWELVEMONKEYS-LICENSE.txt",
                    "licenses/TWELVEMONKEYS-THIRD-PARTY-NOTICES.txt",
                ),
            ("org.abego.treelayout" to "org.abego.treelayout.core") to
                listOf(
                    "licenses/ABEGO-TREELAYOUT-LICENSE.txt",
                ),
            ("org.antlr" to "ST4") to
                listOf(
                    "licenses/STRINGTEMPLATE4-LICENSE.txt",
                    "licenses/ANTLR-SOURCE-NOTICES.txt",
                ),
            ("org.antlr" to "antlr-runtime") to
                listOf(
                    "licenses/ANTLR3-LICENSE.txt",
                    "licenses/ANTLR-SOURCE-NOTICES.txt",
                ),
            ("org.antlr" to "antlr4") to
                listOf(
                    "licenses/ANTLR4-LICENSE.txt",
                    "licenses/ANTLR-SOURCE-NOTICES.txt",
                ),
            ("org.antlr" to "antlr4-intellij-adaptor") to
                listOf(
                    "licenses/ANTLR4-INTELLIJ-ADAPTOR-LICENSE.txt",
                    "licenses/ANTLR4-LICENSE.txt",
                    "licenses/ANTLR-SOURCE-NOTICES.txt",
                ),
            ("org.antlr" to "antlr4-runtime") to
                listOf(
                    "licenses/ANTLR4-LICENSE.txt",
                    "licenses/ANTLR-SOURCE-NOTICES.txt",
                ),
            ("org.eclipse.jdt" to "ecj") to listOf("licenses/EPL-2.0.txt"),
        )

    fun verify(
        archive: File,
        sbom: File,
        projectLicense: File,
        licensesDirectory: File,
        variant: VariantConfig,
        bundleComposeFallback: Boolean,
    ): Int {
        require(archive.isFile) { "Missing plugin archive: $archive" }
        require(sbom.isFile) { "Missing SBOM: $sbom" }
        require(projectLicense.isFile) { "Missing project license: $projectLicense" }
        require(licensesDirectory.isDirectory) {
            "Missing source license inventory: $licensesDirectory"
        }

        licensesDirectory.walkTopDown().forEach { path ->
            require(!Files.isSymbolicLink(path.toPath())) {
                "License inventory must not contain symbolic links: $path"
            }
        }
        val licenseFiles =
            licensesDirectory
                .walkTopDown()
                .filter(File::isFile)
                .sortedBy { it.relativeTo(licensesDirectory).invariantSeparatorsPath }
                .toList()
        require(licenseFiles.isNotEmpty()) { "Source license inventory is empty" }

        val sourceRoot = projectLicense.parentFile
        requiredLicenseFiles.forEach { relative ->
            require(sourceRoot.resolve(relative).isFile) {
                "Missing source license material: $relative"
            }
        }
        skiaVersions.forEach { version ->
            skiaLicensePaths.forEach { relative ->
                require(licensesDirectory.resolve("skia/$version/$relative").isFile) {
                    "Missing Skia $version license material: $relative"
                }
            }
        }

        val pluginVersion =
            verifyArchive(
                archive = archive,
                projectLicense = projectLicense,
                licensesDirectory = licensesDirectory,
                licenseFiles = licenseFiles,
                variant = variant,
                bundleComposeFallback = bundleComposeFallback,
            )
        return verifySbom(
            sbom = sbom,
            sourceRoot = sourceRoot,
            pluginVersion = pluginVersion,
            bundleComposeFallback = bundleComposeFallback,
        )
    }

    private fun verifyArchive(
        archive: File,
        projectLicense: File,
        licensesDirectory: File,
        licenseFiles: List<File>,
        variant: VariantConfig,
        bundleComposeFallback: Boolean,
    ): String =
        ZipFile(archive).use { bundle ->
            val entries = bundle.entries().toList()
            val names = entries.map { it.name }
            require(names.size == names.toSet().size) { "Duplicate ZIP entries" }
            require(
                names.all { name ->
                    name.startsWith("antlr4plus/") && '\\' !in name && ".." !in name.split('/')
                },
            ) {
                "Unexpected plugin layout"
            }
            entries
                .filterNot { it.isDirectory }
                .forEach { entry ->
                    val crc = CRC32()
                    bundle.getInputStream(entry).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            crc.update(buffer, 0, count)
                        }
                    }
                    require(entry.crc < 0 || entry.crc == crc.value) {
                        "Corrupt ZIP entry: ${entry.name}"
                    }
                }
            val nameSet = names.toSet()
            verifyPackagedFile(bundle, nameSet, "LICENSE.md", projectLicense.readBytes())
            require("antlr4plus/LICENSE" !in nameSet) { "Obsolete extensionless root LICENSE" }
            require("antlr4plus/NOTICE" !in nameSet) { "Obsolete root NOTICE" }
            require("antlr4plus/THIRD-PARTY-LICENSES.txt" !in nameSet) {
                "Obsolete root third-party inventory"
            }
            require("antlr4plus/licenses/BUNDLED-NOTICES.txt" !in nameSet) {
                "Obsolete aggregate dependency notice"
            }
            requiredLicenseFiles.forEach { relative ->
                require("antlr4plus/$relative" in nameSet) {
                    "Missing packaged license material: $relative"
                }
            }
            licenseFiles.forEach { licenseFile ->
                val relative = "licenses/" + licenseFile.relativeTo(licensesDirectory).invariantSeparatorsPath
                verifyPackagedFile(bundle, nameSet, relative, licenseFile.readBytes())
            }

            val jars = names.filter { it.endsWith(".jar") }
            require(jars.none { it.substringAfterLast('/').startsWith("antlr4-maven-plugin-") }) {
                "Unexpected ANTLR Maven plugin in distribution (CVE-2026-13502)"
            }
            val compiler = jars.filter { "/kotlin-compiler/" in it }.map { it.substringAfterLast('/') }
            val libraries = jars.filter { it.startsWith("antlr4plus/lib/") }.map { it.substringAfterLast('/') }
            compilerJarPrefixes.forEach { prefix ->
                require(compiler.any { it.startsWith(prefix) }) { "Missing compiler JAR: $prefix" }
            }
            listOf("ecj-", "antlr4-runtime-", "antlr-kotlin-target-", "antlr-kotlin-runtime-jvm-").forEach { prefix ->
                require(libraries.any { it.startsWith(prefix) }) {
                    "Missing runtime JAR: $prefix"
                }
            }
            require(libraries.any { it.matches(Regex("antlr4-\\d.*\\.jar")) }) {
                "Missing ANTLR tool JAR"
            }
            require(
                libraries.none {
                    it.startsWith("kotlin-stdlib") || it.startsWith("kotlinx-coroutines")
                },
            ) {
                "Compiler runtime shadows the IDE runtime in lib/"
            }
            if (bundleComposeFallback) {
                listOf("linux-x64", "linux-arm64", "macos-x64", "macos-arm64", "windows-x64").forEach { platform ->
                    require(libraries.any { it.startsWith("skiko-awt-runtime-$platform-") }) {
                        "Missing Skiko native runtime for $platform"
                    }
                }
                require(libraries.any { it.startsWith("jewel-") }) { "Missing Jewel fallback" }
            } else {
                val forbidden = listOf("skiko-", "jewel-", "desktop-jvm-", "ui-desktop-", "runtime-desktop-")
                require(libraries.none { library -> forbidden.any(library::startsWith) }) {
                    "Unexpected UI fallback in a build that uses IDE Compose"
                }
            }

            val descriptors = mutableListOf<JarSnapshot>()
            jars
                .filter { it.startsWith("antlr4plus/lib/") }
                .forEach { jarName ->
                    val snapshot = bundle.getInputStream(bundle.getEntry(jarName)).use(::readJarSnapshot)
                    require("org/antlr/mojo/antlr4/GrammarDependencies.class" !in snapshot.names) {
                        "Unexpected ANTLR Maven dependency cache reader (CVE-2026-13502)"
                    }
                    if ("META-INF/plugin.xml" !in snapshot.names) return@forEach
                    descriptors += snapshot

                    listOf("KotlinLexerCompatibility", "KotlinLexerCompatibility\$CodePointLexerATNSimulator").forEach {
                        require("com/antlr4plus/parsing/kotlin/$it.class" in snapshot.names) {
                            "Missing Kotlin lexer compatibility class: $it"
                        }
                    }

                    val manifest = snapshot.content("META-INF/MANIFEST.MF").toString(StandardCharsets.UTF_8)
                    require(
                        !Regex(
                                "^(?:Build-JVM|Build-OS|Built-By|Build-Host):",
                                setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
                            )
                            .containsMatchIn(manifest),
                    ) {
                        "Plugin manifest exposes build machine metadata"
                    }
                    require(
                        snapshot.content("META-INF/LICENSE.md").contentEquals(bundle.read("antlr4plus/LICENSE.md")),
                    ) {
                        "Packaged LICENSE.md copies differ"
                    }
                    require("META-INF/LICENSE" !in snapshot.names) {
                        "Obsolete extensionless root LICENSE in plugin JAR"
                    }
                    require("META-INF/NOTICE" !in snapshot.names) {
                        "Obsolete root NOTICE in plugin JAR"
                    }
                    require("META-INF/THIRD-PARTY-LICENSES.txt" !in snapshot.names) {
                        "Obsolete root third-party inventory in plugin JAR"
                    }
                    require("META-INF/licenses/BUNDLED-NOTICES.txt" !in snapshot.names) {
                        "Obsolete aggregate dependency notice in plugin JAR"
                    }
                    licenseFiles.forEach { licenseFile ->
                        val relative = "licenses/" + licenseFile.relativeTo(licensesDirectory).invariantSeparatorsPath
                        require(
                            snapshot.content("META-INF/$relative").contentEquals(bundle.read("antlr4plus/$relative")),
                        ) {
                            "Packaged $relative copies differ"
                        }
                    }
                    require("fonts/OFL.txt" in snapshot.names) { "Missing font license" }
                    require("META-INF/licenses/EPL-2.0.txt" in snapshot.names) {
                        "Missing embedded ECJ license"
                    }
                }

            require(descriptors.size == 1) {
                "Expected one plugin descriptor, got ${descriptors.size}"
            }
            val descriptor = parseDescriptor(descriptors.single().content("META-INF/plugin.xml"))
            require(descriptor.id == "com.antlr4plus") { "Unexpected plugin ID" }
            require(descriptor.version.isNotBlank() && "replaced" !in descriptor.version) {
                "Unpatched plugin version"
            }
            require(archive.name == "antlr4plus-${descriptor.version}-${variant.name}.zip") {
                "ZIP name/version/variant mismatch"
            }
            require(descriptor.sinceBuild == variant.sinceBuild) {
                "Incorrect IDE lower bound for ${variant.name}"
            }
            require(descriptor.untilBuild == variant.untilBuild.ifBlank { null }) {
                "Incorrect IDE upper bound for ${variant.name}"
            }
            if (!bundleComposeFallback) {
                require(
                    descriptor.dependencies.any {
                        it.id == "com.intellij.modules.compose" && !it.optional
                    },
                ) {
                    "IDE Compose dependency must be required"
                }
            }
            descriptor.version
        }

    private fun verifySbom(
        sbom: File,
        sourceRoot: File,
        pluginVersion: String,
        bundleComposeFallback: Boolean,
    ): Int {
        val bom = JsonSlurper().parse(sbom) as? Map<*, *> ?: error("SBOM root must be a JSON object")
        require(bom["bomFormat"] == "CycloneDX") { "Expected a CycloneDX SBOM" }
        val components =
            (bom["components"] as? List<*>)
                ?.map { component ->
                    component as? Map<*, *> ?: error("SBOM component must be a JSON object")
                }
                .orEmpty()
        require(components.isNotEmpty()) { "SBOM has no components" }

        components.forEach { component ->
            val group = component["group"] as? String
            val name = component["name"] as? String
            val version = component["version"] as? String
            val identity = listOfNotNull(group, name, version).joinToString(":")
            val licenses = component["licenses"] as? List<*>
            require(!licenses.isNullOrEmpty()) { "SBOM omits license metadata for $identity" }
            val labels =
                licenses
                    .map { choice ->
                        val choiceMap = choice as? Map<*, *> ?: error("Invalid SBOM license entry for $identity")
                        choiceMap["expression"] as? String
                            ?: run {
                                val license = choiceMap["license"] as? Map<*, *>
                                license?.get("id") as? String ?: license?.get("name") as? String
                            }
                    }
                    .toSet()
            if (labels.any { it != "Apache-2.0" }) {
                val coverage = nonApacheLicenseCoverage[group to name]
                require(coverage != null) {
                    "No packaged-license mapping for non-Apache component: $identity"
                }
                coverage.forEach { relative ->
                    require(sourceRoot.resolve(relative).isFile) {
                        "Missing mapped license for $identity: $relative"
                    }
                }
            }
        }

        val componentNames = components.mapNotNull { it["name"] as? String }.toSet()
        require("antlr4-maven-plugin" !in componentNames) {
            "Unexpected ANTLR Maven plugin in SBOM"
        }
        listOf(
                "ST4",
                "antlr-runtime",
                "antlr4",
                "antlr4-intellij-adaptor",
                "antlr4-runtime",
                "antlr-kotlin-target",
                "ecj",
                "imageio-tiff",
                "kotlin-compiler-embeddable",
                "kotlin-stdlib",
                "kotlinx-coroutines-core-jvm",
                "org.abego.treelayout.core",
            )
            .forEach { required ->
                require(required in componentNames) { "SBOM omits $required" }
            }
        if (bundleComposeFallback) {
            require(componentNames.any { it.startsWith("skiko-") }) { "SBOM omits Skiko fallback" }
            require(componentNames.any { it.startsWith("jewel-") }) { "SBOM omits Jewel fallback" }
        }
        val metadata = bom["metadata"] as? Map<*, *> ?: error("SBOM omits metadata")
        val metadataComponent = metadata["component"] as? Map<*, *> ?: error("SBOM omits metadata component")
        require(metadataComponent["version"] == pluginVersion) {
            "SBOM version differs from plugin version"
        }
        return components.size
    }

    private fun verifyPackagedFile(
        bundle: ZipFile,
        names: Set<String>,
        relative: String,
        expected: ByteArray,
    ) {
        val packaged = "antlr4plus/$relative"
        require(packaged in names) { "Missing $relative" }
        require(bundle.read(packaged).contentEquals(expected)) {
            "Packaged $relative differs from the source checkout"
        }
    }

    private fun ZipFile.read(name: String): ByteArray {
        val entry = getEntry(name) ?: error("Missing ZIP entry: $name")
        return getInputStream(entry).use(InputStream::readBytes)
    }

    private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList {
        while (hasMoreElements()) add(nextElement())
    }

    private data class JarSnapshot(
        val names: Set<String>,
        val selectedContent: Map<String, ByteArray>,
    ) {
        fun content(name: String): ByteArray = selectedContent[name] ?: error("Missing JAR entry: $name")
    }

    private fun readJarSnapshot(input: InputStream): JarSnapshot {
        val names = linkedSetOf<String>()
        val selected = mutableMapOf<String, ByteArray>()
        ZipInputStream(input.buffered()).use { jar ->
            while (true) {
                val entry = jar.nextEntry ?: break
                require(names.add(entry.name)) { "Duplicate JAR entry: ${entry.name}" }
                if (
                    !entry.isDirectory &&
                        (entry.name == "META-INF/plugin.xml" ||
                            entry.name == "META-INF/MANIFEST.MF" ||
                            entry.name == "META-INF/LICENSE.md" ||
                            entry.name == "fonts/OFL.txt" ||
                            entry.name.startsWith("META-INF/licenses/"))
                ) {
                    selected[entry.name] = jar.readBytes()
                }
                jar.closeEntry()
            }
        }
        return JarSnapshot(names, selected)
    }

    private data class PluginDependency(val id: String, val optional: Boolean)

    private data class PluginDescriptor(
        val id: String,
        val version: String,
        val sinceBuild: String?,
        val untilBuild: String?,
        val dependencies: List<PluginDependency>,
    )

    private fun parseDescriptor(xml: ByteArray): PluginDescriptor {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
        val root = factory.newDocumentBuilder().parse(xml.inputStream()).documentElement
        fun children(name: String): List<Element> = buildList {
            val nodes = root.childNodes
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node is Element && node.tagName == name) add(node)
            }
        }
        val ideaVersion =
            children("idea-version").singleOrNull() ?: error("Plugin descriptor must contain one idea-version element")
        return PluginDescriptor(
            id = children("id").singleOrNull()?.textContent?.trim().orEmpty(),
            version = children("version").singleOrNull()?.textContent?.trim().orEmpty(),
            sinceBuild = ideaVersion.getAttribute("since-build").ifBlank { null },
            untilBuild = ideaVersion.getAttribute("until-build").ifBlank { null },
            dependencies =
                children("depends").map { dependency ->
                    PluginDependency(
                        id = dependency.textContent.trim(),
                        optional = dependency.getAttribute("optional").equals("true", ignoreCase = true),
                    )
                },
        )
    }
}
