package com.antlr4plus.parsing

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class ResolvedCompiledClass(
    val binaryName: String,
    val file: File,
)

internal fun newestGeneratedSourceTimestamp(generatedCodeDir: File, log: Logger): Long {
    val root = generatedCodeDir.toPath().toAbsolutePath().normalize()
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return Long.MAX_VALUE

    var newest = 0L
    return try {
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                val name = path.fileName?.toString().orEmpty()
                if (
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        (name.endsWith(".java", ignoreCase = true) || name.endsWith(".kt", ignoreCase = true))
                ) {
                    newest =
                        maxOf(
                            newest,
                            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(),
                        )
                }
            }
        }
        newest
    } catch (failure: Exception) {
        log.warn(
            "Could not determine generated-source freshness in ${generatedCodeDir.absolutePath}",
            failure,
        )
        Long.MAX_VALUE
    }
}

/**
 * Resolves a generated class by simple name and derives its JVM binary name from its path below the compiler output
 * directory. Both runtime compilers preserve package declarations, and compiled output remains the authoritative
 * binary-name source.
 */
internal fun resolveCompiledClass(
    compileOutputDir: File,
    simpleName: String,
    log: Logger,
): ResolvedCompiledClass? {
    val root = compileOutputDir.toPath().toAbsolutePath().normalize()
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return null

    val matches = mutableListOf<Path>()
    try {
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                if (
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        path.fileName.toString() == "$simpleName.class"
                ) {
                    matches.add(path.toAbsolutePath().normalize())
                }
            }
        }
    } catch (failure: Exception) {
        log.warn("Could not scan compiled classes in ${compileOutputDir.absolutePath}", failure)
        return null
    }

    if (matches.size != 1) {
        log.warn(
            "Expected exactly one compiled $simpleName.class below ${compileOutputDir.absolutePath}, " +
                "found ${matches.size}: ${matches.map { root.relativize(it) }}",
        )
        return null
    }

    val classFile = matches.single()
    val relativeClassName = root.relativize(classFile).toString().removeSuffix(".class")
    val binaryName = relativeClassName.replace(File.separatorChar, '.')
    return ResolvedCompiledClass(binaryName, classFile.toFile())
}
