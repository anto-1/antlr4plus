package com.antlr4plus.parsing

import com.antlr4plus.util.GeneratedWorkspaceFileOps
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files

/** Loads the bundled Kotlin compiler separately to avoid conflicts with IDE Kotlin classes. */
object KotlinRuntimeCompiler {
    private val LOG = Logger.getInstance(KotlinRuntimeCompiler::class.java)

    private const val PLUGIN_ID = "com.antlr4plus"
    private const val IDEA_HOME_PROPERTY = "idea.home.path"

    @Volatile private var isolatedClassLoader: URLClassLoader? = null

    @Volatile private var compilerJars: List<File>? = null

    @Volatile private var cachedCompiler: Any? = null

    @Volatile private var cachedExecMethod: java.lang.reflect.Method? = null

    @Volatile private var fakeIdeaHomeInitialized: Boolean = false

    @Volatile private var fakeIdeaHomePath: File? = null

    data class CompilerInfo(
        val source: String,
        val path: String?,
        val version: String?,
        val isAvailable: Boolean,
    )

    fun getCompilerInfo(): CompilerInfo =
        if (isAvailable()) {
            CompilerInfo(
                source = "Embedded (kotlin-compiler-embeddable)",
                path = null,
                version = "2.3.0",
                isAvailable = true,
            )
        } else {
            CompilerInfo(
                source = "Kotlin compiler not available",
                path = null,
                version = null,
                isAvailable = false,
            )
        }

    fun isAvailable(): Boolean =
        try {
            getOrCreateIsolatedClassLoader()
            true
        } catch (e: Exception) {
            LOG.debug("Kotlin compiler not available: ${e.message}")
            false
        }

    /** Loads compiler dependencies from their bundle and ANTLR runtime JARs from plugin lib. */
    private fun locateCompilerJars(): List<File> {
        compilerJars?.let {
            return it
        }

        val pluginDescriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
        if (pluginDescriptor == null) {
            LOG.warn("Could not find plugin descriptor for $PLUGIN_ID")
            throw IllegalStateException("Plugin not found: $PLUGIN_ID")
        }

        val pluginPath = pluginDescriptor.pluginPath
        LOG.info("Plugin path: $pluginPath")

        val libDir = pluginPath.resolve("lib").toFile()
        val compilerBundleDir = pluginPath.resolve("kotlin-compiler").toFile()

        if (!libDir.isDirectory) {
            LOG.warn("Plugin lib directory not found: ${libDir.absolutePath}")
            throw IllegalStateException("Plugin lib directory not found")
        }

        if (!compilerBundleDir.isDirectory) {
            LOG.warn(
                "Bundled Kotlin compiler directory not found: ${compilerBundleDir.absolutePath}",
            )
            throw IllegalStateException(
                "Bundled Kotlin compiler not available. Reinstall the plugin.",
            )
        }

        val compilerBundleJars =
            compilerBundleDir
                .listFiles { file ->
                    file.isFile && file.extension == "jar"
                }
                ?.toList()
                .orEmpty()

        val pluginRuntimePrefixes =
            listOf(
                "antlr-kotlin-target-",
                "antlr-kotlin-runtime-",
                "antlr4-runtime-",
                "antlr4-",
            )
        val pluginRuntimeJars =
            libDir
                .listFiles { file ->
                    file.isFile && file.extension == "jar" && pluginRuntimePrefixes.any(file.name::startsWith)
                }
                ?.toList()
                .orEmpty()
        val jars = (compilerBundleJars + pluginRuntimeJars).sortedBy(File::getName)

        LOG.info("Located ${jars.size} compiler JARs: ${jars.map { it.name }}")

        val hasCompiler = jars.any { it.name.startsWith("kotlin-compiler-embeddable") }
        if (!hasCompiler) {
            LOG.warn("Bundled Kotlin compiler not found")
            throw IllegalStateException(
                "Bundled Kotlin compiler not available. Reinstall the plugin.",
            )
        }

        LOG.info("Found ${jars.size} compiler-related JARs: ${jars.map { it.name }}")
        compilerJars = jars
        return jars
    }

    private fun getOrCreateIsolatedClassLoader(): URLClassLoader {
        isolatedClassLoader?.let {
            return it
        }

        synchronized(this) {
            isolatedClassLoader?.let {
                return it
            }

            val jars = locateCompilerJars()
            val urls = jars.map { it.toURI().toURL() }.toTypedArray()

            val classLoader = URLClassLoader(urls, null)

            try {
                classLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
                LOG.info("Successfully created isolated classloader for Kotlin compiler")
            } catch (e: ClassNotFoundException) {
                classLoader.close()
                throw IllegalStateException("K2JVMCompiler not found in isolated classloader", e)
            }

            isolatedClassLoader = classLoader
            return classLoader
        }
    }

    /** Restricts the compilation classpath to runtime dependencies to reduce scanning. */
    private fun getRuntimeOnlyClasspath(): List<File> =
        locateCompilerJars().filter {
            it.name.startsWith("kotlin-stdlib") ||
                it.name.startsWith("kotlin-reflect") ||
                it.name.startsWith("antlr-kotlin-runtime") ||
                it.name.startsWith("antlr4-runtime") ||
                it.name.startsWith("antlr4-") ||
                it.name.startsWith("annotations")
        }

    /** Creates the minimal IDEA home layout required by the embedded compiler. */
    private fun setupFakeIdeaHome(): File {
        fakeIdeaHomePath?.let {
            return it
        }

        synchronized(this) {
            fakeIdeaHomePath?.let {
                return it
            }

            val tempRoot = File(System.getProperty("java.io.tmpdir")).toPath()
            if (!GeneratedWorkspaceFileOps.ensureDirectory(tempRoot)) {
                throw IllegalStateException(
                    "Kotlin compiler temp root is not a safe directory: $tempRoot",
                )
            }

            val homePath = Files.createTempDirectory(tempRoot, "antlr4plus-kotlin-compiler-home-")
            try {
                val binPath = Files.createDirectory(homePath.resolve("bin"))
                val propertiesPath =
                    Files.writeString(
                        binPath.resolve("idea.properties"),
                        "# Fake IDEA properties for Kotlin compiler\n",
                    )

                // deleteOnExit runs in reverse registration order, so register the parent first.
                homePath.toFile().deleteOnExit()
                binPath.toFile().deleteOnExit()
                propertiesPath.toFile().deleteOnExit()

                val fakeIdeaHome = homePath.toFile()
                fakeIdeaHomePath = fakeIdeaHome
                fakeIdeaHomeInitialized = true
                LOG.info("Initialized fake IDEA home at: ${fakeIdeaHome.absolutePath}")
                return fakeIdeaHome
            } catch (e: Exception) {
                GeneratedWorkspaceFileOps.deleteTree(homePath)
                throw e
            }
        }
    }

    private fun getOrCreateCompiler(loader: URLClassLoader): Pair<Any, java.lang.reflect.Method> {
        cachedCompiler?.let { compiler ->
            cachedExecMethod?.let { method ->
                return compiler to method
            }
        }

        synchronized(this) {
            cachedCompiler?.let { compiler ->
                cachedExecMethod?.let { method ->
                    return compiler to method
                }
            }

            LOG.info("Creating new K2JVMCompiler instance (will be cached)")
            val compilerClass = loader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            val compiler = compilerClass.getDeclaredConstructor().newInstance()
            val execMethod = compilerClass.getMethod("exec", PrintStream::class.java, Array<String>::class.java)

            cachedCompiler = compiler
            cachedExecMethod = execMethod
            LOG.info("K2JVMCompiler instance cached successfully")
            return compiler to execMethod
        }
    }

    sealed class CompilationResult {
        data class Success(val classLoader: URLClassLoader) : CompilationResult()

        data class Failure(val errors: List<String>) : CompilationResult()
    }

    @Synchronized
    fun compile(
        sourceFiles: List<File>,
        classpath: List<File>,
        outputDir: File,
        progressCallback: ((String) -> Unit)? = null,
    ): CompilationResult {
        LOG.info("compile() called with ${sourceFiles.size} Kotlin source files")
        val totalStartTime = System.nanoTime()

        if (sourceFiles.isEmpty()) {
            return CompilationResult.Failure(listOf("No Kotlin source files to compile"))
        }

        val invalidSources = sourceFiles.filterNot {
            GeneratedWorkspaceFileOps.isRegularFile(it.toPath())
        }
        if (invalidSources.isNotEmpty()) {
            return CompilationResult.Failure(
                invalidSources.map {
                    "Source file is missing, not regular, or symbolic: ${it.absolutePath}"
                },
            )
        }

        if (!GeneratedWorkspaceFileOps.ensureDirectory(outputDir.toPath())) {
            return CompilationResult.Failure(
                listOf(
                    "Compiler output directory is not a safe regular directory: ${outputDir.absolutePath}",
                ),
            )
        }

        progressCallback?.invoke("Initializing\nKotlin compiler...")

        var stepStartTime = System.nanoTime()
        val loader: URLClassLoader
        try {
            loader = getOrCreateIsolatedClassLoader()
        } catch (e: Exception) {
            return CompilationResult.Failure(
                listOf(
                    "Kotlin compiler not available.",
                    "Error: ${e.message}",
                ),
            )
        }
        LOG.info(
            "  [TIMING] getOrCreateIsolatedClassLoader: ${(System.nanoTime() - stepStartTime) / 1_000_000}ms",
        )

        progressCallback?.invoke("Preparing\nKotlin classpath...")

        try {
            stepStartTime = System.nanoTime()
            val allClasspath = mutableListOf<File>()
            allClasspath.addAll(getRuntimeOnlyClasspath())
            allClasspath.addAll(classpath)
            val classpathString = allClasspath.joinToString(File.pathSeparator) { it.absolutePath }
            LOG.info(
                "  [TIMING] Build classpath: ${(System.nanoTime() - stepStartTime) / 1_000_000}ms",
            )

            // Use CLI arguments to avoid depending on compiler argument-field layouts.
            val compilerArgs = mutableListOf<String>()
            compilerArgs.add("-d")
            compilerArgs.add(outputDir.absolutePath)
            compilerArgs.add("-classpath")
            compilerArgs.add(classpathString)
            compilerArgs.add("-jvm-target")
            compilerArgs.add("17")
            compilerArgs.add("-nowarn")
            compilerArgs.add("-no-reflect")
            compilerArgs.add("-no-stdlib") // Supplied through the explicit classpath.

            compilerArgs.add("-Xuse-fast-jar-file-system")
            compilerArgs.add("-Xno-call-assertions")
            compilerArgs.add("-Xno-param-assertions")
            compilerArgs.add("-Xno-receiver-assertions")
            compilerArgs.add("-Xno-optimize")
            compilerArgs.add("-Xskip-prerelease-check")
            compilerArgs.add("-Xallow-unstable-dependencies")

            sourceFiles.forEach { compilerArgs.add(it.absolutePath) }

            LOG.info("Running embedded Kotlin compiler (isolated classloader)")
            LOG.info("  Source files: ${sourceFiles.map { it.name }}")
            LOG.info("  Output dir: ${outputDir.absolutePath}")
            LOG.info("  Classpath entries: ${allClasspath.size}")
            LOG.info("  Compiler args: $compilerArgs")

            stepStartTime = System.nanoTime()
            val fakeIdeaHome = setupFakeIdeaHome()
            val oldIdeaHome = System.getProperty(IDEA_HOME_PROPERTY)
            val installedIdeaHome = fakeIdeaHome.absolutePath
            System.setProperty(IDEA_HOME_PROPERTY, installedIdeaHome)
            LOG.info(
                "  [TIMING] Setup fake IDEA home: ${(System.nanoTime() - stepStartTime) / 1_000_000}ms",
            )

            return try {
                stepStartTime = System.nanoTime()
                val (compiler, execMethod) = getOrCreateCompiler(loader)
                LOG.info(
                    "  [TIMING] getOrCreateCompiler: ${(System.nanoTime() - stepStartTime) / 1_000_000}ms",
                )

                progressCallback?.invoke("Compiling\nKotlin sources...")

                val errorStream = ByteArrayOutputStream()
                val printStream = PrintStream(errorStream)

                stepStartTime = System.nanoTime()
                val argsArray = compilerArgs.toTypedArray()
                val result =
                    execMethod.invoke(compiler, printStream, argsArray)
                        ?: return CompilationResult.Failure(
                            listOf("Kotlin compiler returned null result"),
                        )
                val execTimeMs = (System.nanoTime() - stepStartTime) / 1_000_000
                LOG.info("  [TIMING] K2JVMCompiler.exec(): ${execTimeMs}ms")

                val getCodeMethod = result.javaClass.getMethod("getCode")
                val exitCodeResult =
                    getCodeMethod.invoke(result)
                        ?: return CompilationResult.Failure(
                            listOf("Kotlin compiler exit code is null"),
                        )
                val exitCode = exitCodeResult as Int

                printStream.flush()
                val errorOutput = errorStream.toString()

                if (exitCode == 0) {
                    val pluginClassLoader = KotlinRuntimeCompiler::class.java.classLoader
                    val compiledUrls =
                        arrayOf(outputDir.toURI().toURL()) +
                            getRuntimeOnlyClasspath().map { it.toURI().toURL() }.toTypedArray()
                    val classLoader = URLClassLoader(compiledUrls, pluginClassLoader)

                    val totalTimeMs = (System.nanoTime() - totalStartTime) / 1_000_000
                    LOG.info(
                        "  [TIMING] TOTAL compile() time: ${totalTimeMs}ms for ${sourceFiles.size} files",
                    )
                    LOG.info("Kotlin compilation successful, output dir: ${outputDir.absolutePath}")
                    CompilationResult.Success(classLoader)
                } else {
                    val errors =
                        if (errorOutput.isNotBlank()) {
                            errorOutput.lines().filter { it.isNotBlank() }
                        } else {
                            listOf("Kotlin compilation failed with exit code $exitCode")
                        }
                    LOG.warn("Kotlin compilation failed: ${errors.joinToString("; ")}")
                    CompilationResult.Failure(errors)
                }
            } finally {
                // Restore only our value; another subsystem may have replaced this JVM-wide
                // property.
                if (System.getProperty(IDEA_HOME_PROPERTY) == installedIdeaHome) {
                    if (oldIdeaHome != null) {
                        System.setProperty(IDEA_HOME_PROPERTY, oldIdeaHome)
                    } else {
                        System.clearProperty(IDEA_HOME_PROPERTY)
                    }
                } else {
                    LOG.info(
                        "idea.home.path changed during Kotlin compilation; preserving the newer value",
                    )
                }
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "${e.javaClass.simpleName} (no message)"
            LOG.warn("Kotlin compilation error: $errorMsg", e)
            return CompilationResult.Failure(listOf("Kotlin compilation error: $errorMsg"))
        }
    }

    fun getAntlrKotlinRuntimeClasspath(): List<File> =
        try {
            getRuntimeOnlyClasspath().filter {
                it.name.contains("antlr-kotlin-runtime") ||
                    it.name.contains("antlr4-runtime") ||
                    it.name.startsWith("kotlin-stdlib")
            }
        } catch (e: Exception) {
            LOG.warn("Could not locate antlr-kotlin-runtime classpath: ${e.message}")
            emptyList()
        }

    fun findKotlinFiles(directory: File): List<File> =
        GeneratedWorkspaceFileOps.findRegularFilesWithSuffix(directory.toPath(), ".kt")

    /** Initializes compiler caches and attempts a small compilation. Call off the EDT. */
    @Synchronized
    fun warmup(): Boolean {
        if (isWarmedUp()) return true
        return try {
            val startTime = System.nanoTime()
            LOG.info("Warming up Kotlin compiler...")

            val loader = getOrCreateIsolatedClassLoader()
            LOG.info("  Classloader initialized: ${(System.nanoTime() - startTime) / 1_000_000}ms")

            getOrCreateCompiler(loader)
            LOG.info(
                "  Compiler instance created: ${(System.nanoTime() - startTime) / 1_000_000}ms",
            )

            setupFakeIdeaHome()
            LOG.info("  Fake IDEA home ready: ${(System.nanoTime() - startTime) / 1_000_000}ms")

            getRuntimeOnlyClasspath()
            LOG.info("  Runtime classpath loaded: ${(System.nanoTime() - startTime) / 1_000_000}ms")

            val warmupResult = doWarmupCompilation()
            val totalMs = (System.nanoTime() - startTime) / 1_000_000
            LOG.info(
                "Kotlin compiler warmup complete in ${totalMs}ms (warmup compile: $warmupResult)",
            )
            true
        } catch (e: Exception) {
            LOG.warn("Kotlin compiler warmup failed: ${e.message}", e)
            false
        }
    }

    private fun doWarmupCompilation(): String =
        try {
            // Atomic allocation avoids pre-created paths redirecting compilation or cleanup.
            val warmupDir = Files.createTempDirectory("antlr4plus-kotlin-warmup-").toFile()

            try {
                val warmupFile = File(warmupDir, "Warmup.kt")
                warmupFile.writeText("fun warmup() = Unit")

                val outputDir = File(warmupDir, "out")
                outputDir.mkdirs()

                val startTime = System.nanoTime()
                val result = compile(listOf(warmupFile), emptyList(), outputDir)
                val compileMs = (System.nanoTime() - startTime) / 1_000_000

                when (result) {
                    is CompilationResult.Success -> result.classLoader.use { "success in ${compileMs}ms" }
                    is CompilationResult.Failure -> "failed in ${compileMs}ms: ${result.errors.firstOrNull()}"
                }
            } finally {
                GeneratedWorkspaceFileOps.deleteTree(warmupDir.toPath())
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }

    /** Reports whether the required compiler caches and temporary home are initialized. */
    fun isWarmedUp(): Boolean =
        isolatedClassLoader != null && cachedCompiler != null && cachedExecMethod != null && fakeIdeaHomeInitialized
}
