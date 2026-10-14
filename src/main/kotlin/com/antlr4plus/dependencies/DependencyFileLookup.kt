package com.antlr4plus.dependencies

import com.antlr4plus.dependencies.UnifiedDependencyResolver.isWithinBoundary
import com.antlr4plus.util.GeneratedWorkspaceFileOps
import java.io.File
import java.nio.file.Path

/** Selects the nearest source candidate; qualified imports must match its declared package. */
internal fun findNearestSourceFile(
    className: String,
    qualifiedName: String?,
    startDir: File?,
    boundaryRoot: File?,
    candidates: List<File>,
): File? {
    if (startDir == null || boundaryRoot == null) return null
    if (!isWithinBoundary(boundaryRoot, startDir)) return null

    val filenameCandidates = candidates.filter {
        it.name == "$className.java" || it.name == "$className.kt"
    }
    val matchingCandidates =
        if (qualifiedName == null) {
            filenameCandidates
        } else {
            filenameCandidates.filter { extractQualifiedClassName(it) == qualifiedName }
        }

    return matchingCandidates.minWithOrNull(
        compareBy<File> { sourceDistance(startDir.toPath(), it.parentFile.toPath()) }.thenBy { it.absolutePath },
    )
}

internal fun findSourceFilesByPackage(
    packageName: String,
    startDir: File?,
    boundaryRoot: File?,
    candidates: List<File>,
): List<File> {
    if (startDir == null || boundaryRoot == null) return emptyList()
    if (!isWithinBoundary(boundaryRoot, startDir)) return emptyList()
    return candidates
        .filter { source ->
            extractQualifiedClassName(source)?.substringBeforeLast('.', missingDelimiterValue = "") == packageName
        }
        .sortedWith(
            compareBy<File> { sourceDistance(startDir.toPath(), it.parentFile.toPath()) }.thenBy { it.absolutePath },
        )
}

internal fun resolveGrammarFile(grammarName: String, searchDirs: List<File>): File? {
    for (dir in searchDirs) {
        if (!GeneratedWorkspaceFileOps.isDirectory(dir.toPath())) continue

        val withExtension = File(dir, "$grammarName.g4")
        if (GeneratedWorkspaceFileOps.isRegularFile(withExtension.toPath())) {
            return withExtension
        }

        val exactName = File(dir, grammarName)
        if (GeneratedWorkspaceFileOps.isRegularFile(exactName.toPath())) {
            return exactName
        }
    }

    return null
}

private fun sourceDistance(start: Path, candidate: Path): Int {
    val normalizedStart = start.toAbsolutePath().normalize()
    val normalizedCandidate = candidate.toAbsolutePath().normalize()
    val commonLength =
        (0 until minOf(normalizedStart.nameCount, normalizedCandidate.nameCount))
            .takeWhile { index ->
                normalizedStart.getName(index) == normalizedCandidate.getName(index)
            }
            .count()
    return (normalizedStart.nameCount - commonLength) + (normalizedCandidate.nameCount - commonLength)
}

private fun extractQualifiedClassName(file: File): String? =
    try {
        extractQualifiedClassNameFromContent(file.readText(), file.nameWithoutExtension)
    } catch (e: Exception) {
        null
    }
