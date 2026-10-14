import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

buildscript {
    val variant = gradle.extensions.extraProperties["antlr4plus.buildVariant"] as String
    val kotlinVersion =
        when (variant) {
            "newer" -> "2.1.20"
            "modern" -> "2.1.0"
            else -> "1.9.24"
        }

    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
    dependencies {
        if (kotlinVersion.startsWith("2.")) {
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
        } else {
            classpath("org.jetbrains.compose:compose-gradle-plugin:1.6.11")
        }
    }
}

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("src/**/*.kt", "buildSrc/src/**/*.kt")
        ktfmt().kotlinlangStyle().configure { it.setMaxWidth(120) }
    }
    kotlinGradle {
        target("*.gradle.kts", "buildSrc/*.gradle.kts")
        ktfmt().kotlinlangStyle().configure { it.setMaxWidth(120) }
    }
    format("repositoryText") {
        target(
            "*.md",
            "docs/**/*.md",
            ".github/**/*.md",
            ".github/**/*.yml",
            ".github/**/*.yaml",
            ".editorconfig",
            ".gitignore",
            ".gitattributes",
            "gradle.properties",
            "gradle/*.toml",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

group = providers.gradleProperty("pluginGroup").get()

providers.environmentVariable("AGENT_BUILD_DIR").orNull?.let {
    layout.buildDirectory.set(file(it))
}

val buildVariant = gradle.extensions.extraProperties["antlr4plus.buildVariant"] as String
val variantConfig = BuildVariants.byName(buildVariant)
val basePluginVersion = providers.gradleProperty("pluginVersion")
val releasePluginVersion = basePluginVersion.map { variantConfig.releaseVersion(it) }

version = releasePluginVersion.get()

val explicitBundleComposeFallback =
    providers.gradleProperty("bundleComposeFallback").orNull?.let { value ->
        value.toBooleanStrictOrNull()
            ?: throw GradleException(
                "bundleComposeFallback must be 'true' or 'false' (was '$value')",
            )
    }
val bundleComposeFallback = explicitBundleComposeFallback ?: variantConfig.needsComposeFallback

val composeInfo = if (variantConfig.name == "newer") "IDE-bundled" else variantConfig.composeVersion

logger.lifecycle(
    "Building variant: ${variantConfig.name} (Kotlin ${variantConfig.kotlinVersion}, Compose $composeInfo)",
)

logger.lifecycle("  IDE range: ${variantConfig.sinceBuild} - ${variantConfig.untilBuild}")

logger.lifecycle("  Release version: $version")

logger.lifecycle("  Bundle Compose fallback: $bundleComposeFallback")

if (variantConfig.kotlinVersion.startsWith("2.")) {
    @Suppress("AvoidApplyPluginMethod") apply(plugin = "org.jetbrains.kotlin.plugin.compose")
} else {
    @Suppress("AvoidApplyPluginMethod") apply(plugin = "org.jetbrains.compose")
}

fun toPlatformType(name: String): IntelliJPlatformType =
    when (name) {
        "CLion" -> IntelliJPlatformType.CLion
        "RustRover" -> IntelliJPlatformType.RustRover
        else -> throw IllegalArgumentException("Unknown platform type: $name")
    }

val jvmVersion = if (variantConfig.name == "legacy") 17 else 21

kotlin {
    jvmToolchain(jvmVersion)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinJavaToolchain.toolchain.use(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(jvmVersion))
        },
    )
}

sourceSets {
    main {
        java {
            srcDir("src/main/gen")
        }
        kotlin {
            srcDir("src/main/kotlin-${variantConfig.name}")
        }
    }
}

// javac can read the runtime API without making its newer Kotlin metadata visible
// to the legacy plugin compiler. Package the adapter with the main plugin classes.
val kotlinLexerCompatibilityRuntime by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    kotlinLexerCompatibilityRuntime("com.strumenta:antlr-kotlin-runtime-jvm:1.0.9")
}

val compileKotlinLexerCompatibility by
    tasks.registering(JavaCompile::class) {
        source("src/kotlinRuntimeCompat/java")
        classpath = kotlinLexerCompatibilityRuntime
        destinationDirectory.set(layout.buildDirectory.dir("classes/java/kotlinRuntimeCompat"))
        javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(jvmVersion)) })
        options.release.set(jvmVersion)
    }

sourceSets.main {
    output.dir(
        mapOf("builtBy" to compileKotlinLexerCompatibility),
        compileKotlinLexerCompatibility.flatMap { it.destinationDirectory },
    )
}

// Skiko uses a process-wide native library, so each distribution must use one Compose runtime.
// Without fallback JARs, require the IDE Compose module. PatchPluginXmlTask reads the source
// descriptor directly; update its output rather than processResources.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PatchPluginXmlTask>("patchPluginXml") {
    inputs.property("bundleComposeFallback", bundleComposeFallback)
    doLast {
        if (!bundleComposeFallback) {
            val descriptor = outputFile.get().asFile
            val optionalDependency =
                "<depends optional=\"true\" config-file=\"compose-support.xml\">com.intellij.modules.compose</depends>"
            val requiredDependency = "<depends>com.intellij.modules.compose</depends>"
            val content = descriptor.readText()
            if (optionalDependency !in content && requiredDependency !in content) {
                throw GradleException(
                    "Compose module dependency not found in ${descriptor.absolutePath}",
                )
            }
            descriptor.writeText(content.replace(optionalDependency, requiredDependency))
        }
    }
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://packages.jetbrains.team/maven/p/kpm/public/")
    intellijPlatform {
        defaultRepositories()
    }
}

val grammarsV4FixtureDirectory = providers.gradleProperty("grammarsV4Dir")
val rustfmtExecutable = providers.gradleProperty("antlr4plusRustfmt")
val runGrammarsV4Integration =
    gradle.startParameter.taskNames.any {
        it.substringAfterLast(':') == "grammarsV4IntegrationTest"
    }

tasks.test {
    rustfmtExecutable.orNull?.let { executable ->
        systemProperty("antlr4plus.rustfmt", executable)
    }
    // Reuse the platform test task for its IDE bootstrap classpath and sandbox.
    if (runGrammarsV4Integration) {
        // External fixture changes are not tracked by Gradle.
        outputs.upToDateWhen { false }
        filter {
            includeTestsMatching("com.antlr4plus.integration.GrammarsV4FixtureIntegrationTest")
            includeTestsMatching("com.antlr4plus.integration.GrammarsV4BindingIntegrationTest")
        }
        val fixtureDirectory =
            grammarsV4FixtureDirectory.orNull
                ?: throw GradleException(
                    "grammarsV4IntegrationTest requires -PgrammarsV4Dir=/path/to/grammars-v4",
                )
        systemProperty("grammarsV4Dir", fixtureDirectory)
    } else {
        // Run external grammars-v4 fixtures through grammarsV4IntegrationTest.
        exclude("**/GrammarsV4FixtureIntegrationTest.class")
        exclude("**/GrammarsV4BindingIntegrationTest.class")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.register("grammarsV4IntegrationTest") {
    group = "verification"
    description = "Validates all bundled bindings and generates their grammars against grammars-v4"
    dependsOn(tasks.test)
}

configurations.all {
    exclude(group = "com.ibm.icu", module = "icu4j")
}

// Keep the preview compiler runtime outside lib/ so its Kotlin and coroutines versions
// do not shadow the IDE runtime.
val kotlinCompilerBundle: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(
            Category.CATEGORY_ATTRIBUTE,
            objects.named(Category::class.java, Category.LIBRARY),
        )
        attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(LibraryElements::class.java, LibraryElements.JAR),
        )
    }
}

dependencies {
    // Compile against the selected API; bundleComposeFallback selects the runtime source.
    val composeDesktopDep = ComposePackaging.desktopArtifact(variantConfig.composeVersion)
    compileOnly(composeDesktopDep) {
        exclude(group = "org.jetbrains.compose.material")
        exclude(group = "org.jetbrains.kotlinx")
    }

    val jewelCompileArtifact =
        if (variantConfig.name == "legacy") {
            "org.jetbrains.jewel:jewel-ide-laf-bridge-232:${variantConfig.jewelVersion}"
        } else {
            "org.jetbrains.jewel:jewel-ide-laf-bridge-243:0.27.0"
        }
    compileOnly(jewelCompileArtifact) {
        exclude(group = "org.jetbrains.kotlinx")
    }

    // Match the selected test IDE: newer IDEs use the named limitedParallelism overload.
    val coroutinesTestVersion = if (variantConfig.name == "newer") "1.10.2" else "1.8.1"
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:$coroutinesTestVersion") {
        // Use IntelliJ's patched coroutines core, which includes IntelliJCoroutines helpers.
        isTransitive = false
    }
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${variantConfig.kotlinVersion}") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    testImplementation("org.mockito:mockito-core:5.12.0")
    // Tests need the same Compose runtime as the packaged plugin.
    testCompileOnly(composeDesktopDep) {
        exclude(group = "org.jetbrains.compose.material")
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    if (bundleComposeFallback) {
        testRuntimeOnly(composeDesktopDep) {
            exclude(group = "org.jetbrains.compose.material")
            exclude(group = "org.jetbrains.kotlin")
            exclude(group = "org.jetbrains.kotlinx")
        }
        testRuntimeOnly(jewelCompileArtifact) {
            exclude(group = "org.jetbrains.kotlin")
            exclude(group = "org.jetbrains.kotlinx")
        }
    }

    implementation("org.eclipse.jdt:ecj:3.44.0")

    // The plugin generates user grammars at runtime; its own parsers are checked in.
    implementation("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-intellij-adaptor:0.1") {
        exclude(group = "com.ibm.icu", module = "icu4j")
    }

    // Access Kotlin target/runtime types through reflection to support legacy Kotlin compilation.
    runtimeOnly("com.strumenta:antlr-kotlin-target:1.0.9") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    runtimeOnly("com.strumenta:antlr-kotlin-runtime:1.0.9") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }

    // Bundle the isolated preview compiler explicitly so preview needs no downloads.
    listOf(
            "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.0",
            "org.jetbrains.kotlin:kotlin-stdlib:2.3.0",
            "org.jetbrains.kotlin:kotlin-reflect:2.3.0",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1",
            "org.jetbrains:annotations:24.1.0",
        )
        .forEach { coordinate ->
            kotlinCompilerBundle(coordinate)
        }

    implementation("com.twelvemonkeys.imageio:imageio-tiff:3.12.0")

    intellijPlatform {
        when (variantConfig.name) {
            "newer" -> {
                intellijIdea("2025.3")
            }
            "modern" -> {
                create(providers.gradleProperty("platformType").get(), "2024.3")
            }
            else -> {
                create(
                    providers.gradleProperty("platformType").get(),
                    providers.gradleProperty("platformVersion").get(),
                )
            }
        }
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
    // Use the IDE stdlib to avoid shadowing platform APIs with our compiler's version.
    compileOnly(kotlin("stdlib-jdk8"))
}

val composeFallback: Configuration by configurations.creating {
    isTransitive = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(
            Category.CATEGORY_ATTRIBUTE,
            objects.named(Category::class.java, Category.LIBRARY),
        )
        attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(LibraryElements::class.java, LibraryElements.JAR),
        )
        attribute(Attribute.of("ui", String::class.java), "awt")
    }
}

dependencies {
    // Populate this configuration even when disabled; dedicated non-IDEA builds can opt in.
    // Include each supported platform's Skiko native JAR; Gradle deduplicates shared JARs.
    val composeVersion = variantConfig.composeVersion
    listOf(
            "org.jetbrains.compose.desktop:desktop-jvm-linux-x64:$composeVersion",
            "org.jetbrains.compose.desktop:desktop-jvm-linux-arm64:$composeVersion",
            "org.jetbrains.compose.desktop:desktop-jvm-macos-x64:$composeVersion",
            "org.jetbrains.compose.desktop:desktop-jvm-macos-arm64:$composeVersion",
            "org.jetbrains.compose.desktop:desktop-jvm-windows-x64:$composeVersion",
        )
        .forEach { platformDep ->
            composeFallback(platformDep) {
                exclude(group = "org.jetbrains.compose.material")
                exclude(group = "org.jetbrains.kotlinx")
            }
        }
    val jewelBridge = if (variantConfig.name == "legacy") "232" else "243"
    composeFallback(
        "org.jetbrains.jewel:jewel-ide-laf-bridge-$jewelBridge:${variantConfig.jewelVersion}",
    ) {
        exclude(group = "org.jetbrains.kotlinx")
    }
}

val copyComposeFallbackToSandbox by
    tasks.registering(Copy::class) {
        description = ""
        enabled = bundleComposeFallback
        val prepareSandboxTask =
            tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>(
                "prepareSandbox",
            )
        dependsOn(prepareSandboxTask)
        from(composeFallback)
        into(prepareSandboxTask.flatMap { it.sandboxPluginsDirectory.dir("antlr4plus/lib") })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        composeFallbackExcludes.forEach { exclude(it) }

        doLast {
            val targetDir = prepareSandboxTask.get().sandboxPluginsDirectory.get().asFile.resolve("antlr4plus/lib")
            val composeJars = targetDir.listFiles()?.filter { it.extension == "jar" } ?: emptyList()
            logger.lifecycle("Compose fallback JARs copied to lib/: ${composeJars.size} JARs")
        }
    }

// Copy-based sandbox preparation can retain removed dependencies. Purge stale platform
// runtimes so incremental builds do not repackage them and shadow the IDE.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    from(rootProject.file("LICENSE.md")) { into("antlr4plus") }
    from(rootProject.file("licenses")) { into("antlr4plus/licenses") }
    from(kotlinCompilerBundle) { into("antlr4plus/kotlin-compiler") }

    doLast {
        val pluginRoot = sandboxPluginsDirectory.get().dir("antlr4plus").asFile
        val obsoleteLegalFiles =
            listOf(
                pluginRoot.resolve("LICENSE"),
                pluginRoot.resolve("NOTICE"),
                pluginRoot.resolve("THIRD-PARTY-LICENSES.txt"),
                pluginRoot.resolve("licenses/BUNDLED-NOTICES.txt"),
            )
        obsoleteLegalFiles.forEach { obsoleteFile ->
            if (obsoleteFile.exists() && !obsoleteFile.delete()) {
                throw GradleException("Failed to remove stale legal inventory: $obsoleteFile")
            }
        }
        val pluginLib = pluginRoot.resolve("lib")
        val stalePlatformRuntimes =
            pluginLib
                .listFiles()
                ?.filter { file ->
                    file.isFile &&
                        (file.name.startsWith("kotlin-stdlib") ||
                            file.name.startsWith("kotlinx-coroutines") ||
                            (!bundleComposeFallback && ComposePackaging.isIdeProvidedRuntime(file.name)))
                }
                .orEmpty()
        stalePlatformRuntimes.forEach { staleJar ->
            if (!staleJar.delete()) {
                throw GradleException(
                    "Failed to remove stale platform runtime: ${staleJar.absolutePath}",
                )
            }
        }
    }
}

tasks.named("buildSearchableOptions") {
    dependsOn(copyComposeFallbackToSandbox)
}

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("antlr4plus")
    archiveClassifier.set(buildVariant)
    dependsOn(copyComposeFallbackToSandbox)
    mustRunAfter(copyComposeFallbackToSandbox)

    doLast {
        if (!bundleComposeFallback) {
            val archive =
                outputs.files.files.singleOrNull { it.extension == "zip" }
                    ?: throw GradleException(
                        "Could not identify the buildPlugin ZIP for Compose packaging verification",
                    )
            ComposePackaging.verifyIdeProvidedArchive(archive)
            logger.lifecycle(
                "Verified IDE-provided Compose build contains no fallback Compose/Skiko JARs",
            )
        }
    }
}

fun createFallbackCopyTask(taskSuffix: String, needsFallback: Boolean) {
    val prepareSandboxTaskName = "prepareSandbox$taskSuffix"
    tasks.register<Copy>("copyComposeFallback$taskSuffix") {
        description = ""
        group = "" // Hide this internal task from the task list.
        enabled = needsFallback
        val prepareSandboxTask =
            tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>(
                prepareSandboxTaskName,
            )
        dependsOn(prepareSandboxTask)
        from(composeFallback)
        into(prepareSandboxTask.flatMap { it.sandboxPluginsDirectory.dir("antlr4plus/lib") })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        composeFallbackExcludes.forEach { exclude(it) }
    }
}

ideaVersions.forEach { ide ->
    createFallbackCopyTask("_runIde${ide.taskSuffix}", needsFallback = ide.needsComposeFallback)
}

nonIdeaIdeConfigs.forEach { config ->
    createFallbackCopyTask("_${config.taskName}", needsFallback = true)
}

intellijPlatform {
    // Use a filesystem-safe folder name; plugin.xml supplies the display name.
    projectName = "antlr4plus"

    pluginConfiguration {
        version = releasePluginVersion

        description =
            providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                with(it.lines()) {
                    if (!containsAll(listOf(start, end))) {
                        throw GradleException(
                            "Plugin description section not found in README.md:\n$start ... $end",
                        )
                    }
                    subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
                }
            }

        val changelog = project.changelog // Capture the extension for configuration-cache compatibility.
        // Strip the optional product suffix (.1/.2/.3) for changelog lookup.
        changeNotes = releasePluginVersion.map { pluginVersion ->
            val baseVersion = pluginVersion.replace(Regex("([0-9]+\\.[0-9]+\\.[0-9]+)\\.[123]$"), "$1")
            with(changelog) {
                renderItem(
                    (getOrNull(baseVersion) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild.set(variantConfig.sinceBuild)
            // An empty bound omits until-build from the descriptor.
            if (variantConfig.untilBuild.isNotEmpty()) {
                untilBuild.set(variantConfig.untilBuild)
            }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Use the prerelease label as the Marketplace channel.
        channels =
            providers.gradleProperty("pluginVersion").map {
                listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
            }
    }

    pluginVerification {
        // Exclude platform/fallback UI packages from verifier compatibility checks.
        // Plugin Verifier accepts colon-separated package prefixes.
        freeArgs =
            listOf(
                "-external-prefixes",
                "androidx:org.jetbrains.jewel:org.jetbrains.skiko:org.jetbrains.compose",
            )

        ides {
            // Verify one representative IDE per variant to limit disk usage.
            when (variantConfig.name) {
                "legacy" -> create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.6")
                "modern" -> create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
                "newer" -> create(IntelliJPlatformType.IntellijIdea, "2025.3.1")
            }
        }
    }
}

changelog {
    groups.empty()
}

tasks.cyclonedxDirectBom {
    includeConfigs.set(
        listOf("runtimeClasspath", "kotlinCompilerBundle") +
            if (bundleComposeFallback) listOf("composeFallback") else emptyList(),
    )
    skipConfigs.set(listOf(".*test.*"))
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    includeBomSerialNumber.set(true)
    includeLicenseText.set(false)
    componentVersion.set(project.version.toString())
    jsonOutput.set(layout.buildDirectory.file("reports/sbom.json"))
    xmlOutput.unsetConvention() // JSON only.
}

val verifyDistribution by
    tasks.registering(VerifyDistributionTask::class) {
        group = "verification"
        description = "Verify the selected variant's plugin ZIP, licenses, runtimes, IDE bounds, and SBOM"
        dependsOn("buildPlugin", "cyclonedxDirectBom")
        archiveFile.set(tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile })
        sbomFile.set(layout.buildDirectory.file("reports/sbom.json"))
        projectLicenseFile.set(layout.projectDirectory.file("LICENSE.md"))
        licensesDirectory.set(layout.projectDirectory.dir("licenses"))
        variantName.set(variantConfig.name)
        composeFallbackBundled.set(bundleComposeFallback)
    }

// Omit host JDK and OS details from the packaged manifest.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.GenerateManifestTask>("generateManifest") {
    doLast {
        val output = generatedManifest.get().asFile
        output.writeText(
            output
                .readLines()
                .filterNot { line ->
                    line.startsWith("Build-JVM:") || line.startsWith("Build-OS:")
                }
                .joinToString("\n", postfix = "\n"),
        )
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    processResources {
        from(rootProject.file("LICENSE.md")) { into("META-INF") }
        from(rootProject.file("licenses")) { into("META-INF/licenses") }
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    // Avoid launching an IDE for the optional index during offline packaging.
    named("buildSearchableOptions") {
        enabled = false
    }

    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

intellijPlatformTesting {
    runIde {
        // Isolate each IDE's sandbox and fallback runtime.
        ideaVersions.forEach { ide ->
            register("runIde${ide.taskSuffix}") {
                type =
                    if (ide.shortCode in listOf("253", "261")) IntelliJPlatformType.IntellijIdea
                    else IntelliJPlatformType.IntellijIdeaCommunity
                version =
                    when (ide.shortCode) {
                        "253" -> "2025.3.1"
                        else -> ide.fullVersion
                    }
                sandboxDirectory = layout.buildDirectory.dir("idea-sandbox-runIde${ide.taskSuffix}")
                task {
                    jvmArgs("-Xmx2G", "-Dkotlinx.coroutines.debug=off")
                }
            }
        }
        nonIdeaIdeConfigs.forEach { config ->
            register(config.taskName) {
                type = toPlatformType(config.platformType)
                version = config.version
                sandboxDirectory = layout.buildDirectory.dir("idea-sandbox-${config.taskName}")
                task {
                    jvmArgs("-Xmx2G", "-Dkotlinx.coroutines.debug=off")
                }
            }
        }
    }
}

tasks.runIde {
    dependsOn(copyComposeFallbackToSandbox)
    jvmArgs("-Xmx2G", "-Dkotlinx.coroutines.debug=off")
}

ideaVersions.forEach { ide ->
    val launchTask = "runIde${ide.taskSuffix}"
    val fallbackTask = "copyComposeFallback_runIde${ide.taskSuffix}"
    val launchDependencies = mutableListOf(launchTask)
    if (ide.needsComposeFallback) {
        launchDependencies += fallbackTask
        tasks.named(launchTask) { mustRunAfter(fallbackTask) }
    }
    if (ide.shortCode in setOf("232", "242", "243", "251", "252", "253", "261")) {
        val cleanCache =
            tasks.register("cleanPluginCache_${ide.shortCode}") {
                group = "intellij platform"
                description = "Clear plugin cache for IDE ${ide.fullVersion}"
                doLast {
                    layout.buildDirectory
                        .dir("idea-sandbox-runIde${ide.taskSuffix}/plugins")
                        .get()
                        .asFile
                        .deleteRecursively()
                }
            }
        launchDependencies += cleanCache.name
        tasks.named(launchTask) { mustRunAfter(cleanCache) }
    }
    tasks.register("runIde_${ide.shortCode}") {
        group = "intellij platform"
        description = "Launch IDE ${ide.fullVersion}"
        dependsOn(launchDependencies)
    }
}

nonIdeaIdeConfigs.forEach { config ->
    tasks.named(config.taskName) {
        dependsOn("copyComposeFallback_${config.taskName}")
    }
}

// Isolate child outputs so cleaning one variant cannot delete another's release artifacts.
fun buildVariantDistribution(variant: VariantConfig) {
    val releaseRoot = layout.buildDirectory.get().asFile
    val variantDirectory = releaseRoot.resolve("variants/${variant.name}")
    val wrapper = listOf("./gradlew")
    val properties =
        gradle.startParameter.projectProperties
            .filterKeys { it != "buildVariant" }
            .map { (key, value) -> "-P$key=$value" }
    val command = buildList {
        addAll(wrapper)
        addAll(
            listOf(
                "clean",
                ":buildSrc:check",
                "check",
                "verifyDistribution",
                // The explicit buildSrc checks and root clean both touch the isolated variant tree.
                // Serialize them so clean cannot race a test compilation that is still publishing
                // classes.
                "--no-daemon",
                "--max-workers=1",
                "--project-cache-dir",
                releaseRoot.resolve("variant-caches/${variant.name}").absolutePath,
            ),
        )
        addAll(properties)
        addAll(listOf("-PbuildVariant=${variant.name}"))
        addAll(if (gradle.startParameter.isOffline) listOf("--offline") else emptyList())
    }
    val processBuilder = ProcessBuilder(command).directory(projectDir).redirectErrorStream(true)
    processBuilder.environment()["AGENT_BUILD_DIR"] = variantDirectory.absolutePath
    val process = processBuilder.start()
    process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { logger.lifecycle(it) }
    }
    val result = process.waitFor()
    if (result != 0) {
        throw GradleException("${variant.name} build failed with exit code $result")
    }
    val archiveName = "antlr4plus-${variant.releaseVersion(basePluginVersion.get())}-${variant.name}.zip"
    ReleaseArtifacts.collect(variantDirectory, releaseRoot.resolve("distributions"), archiveName)
    logger.lifecycle(
        "${variant.name} plugin built: ${releaseRoot.resolve("distributions/$archiveName")}",
    )
}

BuildVariants.all.forEach { variant ->
    tasks.register("build${variant.name.replaceFirstChar { it.uppercase() }}") {
        group = "variant"
        description =
            "Build plugin and SBOM for ${variant.name} (${variant.sinceBuild}-${variant.untilBuild.ifBlank { "latest" }})"
        doLast { buildVariantDistribution(variant) }
    }
}

tasks.register("buildAllVariants") {
    group = "variant"
    description = "Build, verify, and retain all plugin ZIPs, SBOMs, and SHA-256 checksums"
    doLast {
        BuildVariants.all.forEach { buildVariantDistribution(it) }
        ReleaseArtifacts.verifyChecksums(layout.buildDirectory.get().dir("distributions").asFile)
        logger.lifecycle(
            "All variants built in ${layout.buildDirectory.get().dir("distributions")}",
        )
    }
}

tasks.register("verifyReleaseDistributions") {
    group = "verification"
    description = "Reverify every retained release ZIP, SBOM, and SHA-256 checksum"
    val distributions = layout.buildDirectory.dir("distributions")
    inputs.dir(distributions)
    inputs.file(layout.projectDirectory.file("LICENSE.md"))
    inputs.dir(layout.projectDirectory.dir("licenses"))
    doLast {
        val directory = distributions.get().asFile
        BuildVariants.all.forEach { variant ->
            val baseName = "antlr4plus-${variant.releaseVersion(basePluginVersion.get())}-${variant.name}"
            val components =
                DistributionVerifier.verify(
                    archive = directory.resolve("$baseName.zip"),
                    sbom = directory.resolve("$baseName.sbom.json"),
                    projectLicense = layout.projectDirectory.file("LICENSE.md").asFile,
                    licensesDirectory = layout.projectDirectory.dir("licenses").asFile,
                    variant = variant,
                    bundleComposeFallback = explicitBundleComposeFallback ?: variant.needsComposeFallback,
                )
            logger.lifecycle("Verified $baseName.zip ($components SBOM components)")
        }
        ReleaseArtifacts.verifyChecksums(directory)
        logger.lifecycle("Verified every retained release distribution and checksum in $directory")
    }
}
