package com.antlr4plus.parsing

import com.antlr4plus.util.GeneratedWorkspaceFileOps
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.URLClassLoader
import javax.tools.DiagnosticCollector
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject

/** Access to the bundled Eclipse Compiler for Java. */
object EcjCompiler {
    private val LOG = Logger.getInstance(EcjCompiler::class.java)

    @Volatile private var cachedCompiler: JavaCompiler? = null

    @Volatile private var ecjAvailable: Boolean? = null

    fun isAvailable(): Boolean {
        ecjAvailable?.let {
            return it
        }

        val available =
            try {
                Class.forName("org.eclipse.jdt.internal.compiler.tool.EclipseCompiler")
                true
            } catch (e: ClassNotFoundException) {
                LOG.info("ECJ not available: ${e.message}")
                false
            }
        ecjAvailable = available
        return available
    }

    fun getCompiler(): JavaCompiler? {
        cachedCompiler?.let {
            return it
        }

        synchronized(this) {
            cachedCompiler?.let {
                return it
            }
            return try {
                val compilerClass = Class.forName("org.eclipse.jdt.internal.compiler.tool.EclipseCompiler")
                val compiler = compilerClass.getDeclaredConstructor().newInstance() as JavaCompiler
                cachedCompiler = compiler
                LOG.info("ECJ compiler loaded successfully")
                compiler
            } catch (e: Exception) {
                LOG.warn("Failed to load ECJ compiler: ${e.message}")
                null
            }
        }
    }

    /** Reports the configured bundled version when ECJ is available. */
    fun getVersion(): String? =
        try {
            "3.44.0"
        } catch (e: Exception) {
            null
        }

    data class EcjInfo(
        val version: String?,
        val isAvailable: Boolean,
    )

    fun getInfo(): EcjInfo =
        EcjInfo(
            version = if (isAvailable()) getVersion() else null,
            isAvailable = isAvailable(),
        )

    /** Compiles sources with package declarations intact; suppressOutput captures ECJ stderr. */
    @Synchronized
    fun compile(
        sourceFiles: List<File>,
        classpath: List<File>,
        outputDir: File,
        suppressOutput: Boolean = false,
    ): RuntimeCompiler.CompilationResult {
        LOG.info(
            "ECJ compile() called with ${sourceFiles.size} source files, outputDir: ${outputDir.absolutePath}",
        )

        if (sourceFiles.isEmpty()) {
            LOG.warn("No source files to compile")
            return RuntimeCompiler.CompilationResult.Failure(listOf("No source files to compile"))
        }

        val invalidSources = sourceFiles.filterNot {
            GeneratedWorkspaceFileOps.isRegularFile(it.toPath())
        }
        if (invalidSources.isNotEmpty()) {
            return RuntimeCompiler.CompilationResult.Failure(
                invalidSources.map {
                    "Source file is missing, not regular, or symbolic: ${it.absolutePath}"
                },
            )
        }

        if (!GeneratedWorkspaceFileOps.ensureDirectory(outputDir.toPath())) {
            return RuntimeCompiler.CompilationResult.Failure(
                listOf(
                    "Compiler output directory is not a safe regular directory: ${outputDir.absolutePath}",
                ),
            )
        }

        val compiler = getCompiler()
        if (compiler == null) {
            LOG.warn("ECJ compiler not available")
            return RuntimeCompiler.CompilationResult.Failure(listOf("ECJ compiler not available"))
        }

        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val fileManager = compiler.getStandardFileManager(diagnostics, null, null)

        fileManager.use { manager ->
            val compilationUnits = manager.getJavaFileObjectsFromFiles(sourceFiles)

            val classpathString = classpath.joinToString(File.pathSeparator) { it.absolutePath }

            val options = mutableListOf<String>()
            if (classpathString.isNotBlank()) {
                options.add("-classpath")
                options.add(classpathString)
            }
            options.add("-d")
            options.add(outputDir.absolutePath)
            options.add("-source")
            options.add("11")
            options.add("-target")
            options.add("11")
            options.add("-nowarn")

            val outputWriter = if (suppressOutput) java.io.StringWriter() else null

            val task =
                compiler.getTask(
                    outputWriter,
                    manager,
                    diagnostics,
                    options,
                    null,
                    compilationUnits,
                )

            val success = task.call()

            if (success) {
                // Compiled classes need the plugin ANTLR runtime through their parent loader.
                val pluginClassLoader = EcjCompiler::class.java.classLoader
                val urls = arrayOf(outputDir.toURI().toURL())
                val classLoader = URLClassLoader(urls, pluginClassLoader)
                LOG.info("ECJ compilation successful, created URLClassLoader")
                return RuntimeCompiler.CompilationResult.Success(classLoader)
            } else {
                val errors =
                    diagnostics.diagnostics.map { diagnostic ->
                        val source = diagnostic.source?.name ?: "unknown"
                        val line = diagnostic.lineNumber
                        val message = diagnostic.getMessage(null)
                        "$source:$line: $message"
                    }
                LOG.info("ECJ compilation failed: ${errors.joinToString("\n")}")
                return RuntimeCompiler.CompilationResult.Failure(errors)
            }
        }
    }
}
