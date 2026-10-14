package com.antlr4plus.parsing

import com.antlr4plus.util.GeneratedWorkspaceFileOps
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.URLClassLoader

/** Compiles generated Java parsers and their support sources with bundled ECJ. */
object RuntimeCompiler {
    private val LOG = Logger.getInstance(RuntimeCompiler::class.java)

    data class CompilerInfo(
        val source: String,
        val path: String?,
        val version: String?,
        val isAvailable: Boolean,
    )

    fun getCompilerInfo(): CompilerInfo {
        if (EcjCompiler.isAvailable()) {
            val ecjInfo = EcjCompiler.getInfo()
            return CompilerInfo(
                source = if (ecjInfo.version != null) "v${ecjInfo.version}" else "ECJ",
                path = null,
                version = ecjInfo.version,
                isAvailable = true,
            )
        }
        return CompilerInfo(
            source = "ECJ not available",
            path = null,
            version = null,
            isAvailable = false,
        )
    }

    sealed class CompilationResult {
        data class Success(val classLoader: URLClassLoader) : CompilationResult()

        data class Failure(val errors: List<String>) : CompilationResult()
    }

    /**
     * Compiles base classes before generated sources. If that fails, compiles both together so base classes can
     * reference generated parser constants.
     */
    fun compileTwoPasses(
        generatedFiles: List<File>,
        baseClassFiles: List<File>,
        classpath: List<File>,
        outputDir: File,
    ): CompilationResult {
        LOG.info(
            "compileTwoPasses() called with ${generatedFiles.size} generated files, ${baseClassFiles.size} base class files",
        )
        LOG.info("  Generated file paths: ${generatedFiles.map { it.absolutePath }}")
        LOG.info("  Base class file paths: ${baseClassFiles.map { it.absolutePath }}")

        if (baseClassFiles.isEmpty()) {
            return compile(generatedFiles, classpath, outputDir)
        }

        if (generatedFiles.isEmpty()) {
            return compile(baseClassFiles, classpath, outputDir)
        }

        // Base classes may need generated constants; suppress this provisional failure output.
        LOG.info("Pass 1: Compiling ${baseClassFiles.size} base class files")
        val pass1Result = compile(baseClassFiles, classpath, outputDir, suppressOutput = true)

        if (pass1Result is CompilationResult.Failure) {
            LOG.info(
                "Pass 1 failed (expected if base classes reference generated code), trying single-pass compilation",
            )
            LOG.info("  generatedFiles (${generatedFiles.size}): ${generatedFiles.map { it.name }}")
            LOG.info("  baseClassFiles (${baseClassFiles.size}): ${baseClassFiles.map { it.name }}")
            val allFiles = generatedFiles + baseClassFiles
            LOG.info("  allFiles for single-pass (${allFiles.size}): ${allFiles.map { it.name }}")
            return compile(allFiles, classpath, outputDir)
        }

        LOG.info("Pass 2: Compiling ${generatedFiles.size} generated files")
        val extendedClasspath = classpath + outputDir
        val pass2Result = compile(generatedFiles, extendedClasspath, outputDir)
        if (pass2Result is CompilationResult.Failure) {
            LOG.warn("Pass 2 failed: ${pass2Result.errors}")
            return pass2Result
        }

        return pass2Result
    }

    fun compile(
        sourceFiles: List<File>,
        classpath: List<File>,
        outputDir: File,
        suppressOutput: Boolean = false,
    ): CompilationResult {
        LOG.info(
            "compile() called with ${sourceFiles.size} source files, outputDir: ${outputDir.absolutePath}",
        )

        if (sourceFiles.isEmpty()) {
            LOG.warn("No source files to compile")
            return CompilationResult.Failure(listOf("No source files to compile"))
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

        if (EcjCompiler.isAvailable()) {
            LOG.info("Using ECJ (bundled compiler)")
            return EcjCompiler.compile(sourceFiles, classpath, outputDir, suppressOutput)
        }

        return CompilationResult.Failure(
            listOf(
                "The bundled Java compiler is unavailable. Reinstall ANTLR4+ to restore it.",
            ),
        )
    }

    fun findJavaFiles(directory: File): List<File> =
        GeneratedWorkspaceFileOps.findRegularFilesWithSuffix(directory.toPath(), ".java")

    fun getAntlrRuntimeClasspath(): List<File> {
        val classpath = mutableListOf<File>()
        val pluginClassLoader = RuntimeCompiler::class.java.classLoader

        LOG.info(
            "Getting ANTLR runtime classpath using plugin classloader: ${pluginClassLoader.javaClass.name}",
        )

        val antlrClasses =
            listOf(
                "org.antlr.v4.runtime.Parser",
                "org.antlr.v4.runtime.Lexer",
                "org.antlr.v4.runtime.CharStream",
                "org.antlr.v4.Tool",
            )

        for (className in antlrClasses) {
            try {
                val clazz = pluginClassLoader.loadClass(className)
                val location = clazz.protectionDomain?.codeSource?.location
                if (location != null) {
                    val file = File(location.toURI())
                    if (file.exists() && !classpath.contains(file)) {
                        LOG.info("Found classpath entry from $className: ${file.absolutePath}")
                        classpath.add(file)
                    }
                }
            } catch (e: ClassNotFoundException) {
                LOG.debug("Class not found: $className")
            } catch (e: Exception) {
                LOG.warn("Error locating class $className: ${e.message}")
            }
        }

        if (classpath.isEmpty()) {
            LOG.info("Primary method found no entries, trying classloader URL extraction")
            try {
                val getUrlsMethod = pluginClassLoader.javaClass.getMethod("getUrls")
                @Suppress("UNCHECKED_CAST") val urls = getUrlsMethod.invoke(pluginClassLoader) as? List<java.net.URL>
                urls?.forEach { url ->
                    if (url.protocol == "file") {
                        val file = File(url.toURI())
                        if (file.exists() && !classpath.contains(file)) {
                            classpath.add(file)
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.debug("Could not extract URLs from classloader: ${e.message}")
            }
        }

        if (classpath.isEmpty()) {
            LOG.info("Trying system classpath as last resort")
            val systemClasspath = System.getProperty("java.class.path", "")
            systemClasspath.split(File.pathSeparator).forEach { path ->
                val file = File(path)
                if (file.exists() && (path.contains("antlr") || path.contains("ANTLR"))) {
                    if (!classpath.contains(file)) {
                        classpath.add(file)
                    }
                }
            }
        }

        LOG.info("Final classpath has ${classpath.size} entries: ${classpath.map { it.name }}")
        return classpath
    }
}
