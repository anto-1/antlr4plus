package com.antlr4plus.filebinding

import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Canonical project-boundary checks for persisted bindings, using forward-slash relative paths. */
internal object ProjectPathPolicy {

    private val windowsAbsolutePath = Regex("^[A-Za-z]:[/\\\\].*")

    fun resolve(root: Path, relativePath: String): Path? {
        if (relativePath.isBlank() || '\u0000' in relativePath || '\\' in relativePath) return null
        if (windowsAbsolutePath.matches(relativePath)) return null

        return try {
            val supplied = Path.of(relativePath)
            if (supplied.isAbsolute || supplied.any { it.toString() == ".." }) return null

            val canonicalRoot = root.toFile().canonicalFile.toPath()
            val candidate = canonicalRoot.resolve(supplied).normalize().toFile().canonicalFile.toPath()
            candidate.takeIf { it != canonicalRoot && it.startsWith(canonicalRoot) }
        } catch (_: InvalidPathException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun relativize(root: Path, target: Path): String? {
        return try {
            val canonicalRoot = root.toFile().canonicalFile.toPath()
            val canonicalTarget = target.toFile().canonicalFile.toPath()
            if (canonicalTarget == canonicalRoot || !canonicalTarget.startsWith(canonicalRoot)) return null

            val relative = canonicalRoot.relativize(canonicalTarget).toString().replace('\\', '/')
            relative.takeIf { resolve(canonicalRoot, it) == canonicalTarget }
        } catch (_: Exception) {
            null
        }
    }

    /** Computes a display path by path components without filesystem access. */
    fun relativeDisplayPath(fromDirectory: Path, target: Path): String? {
        return try {
            val normalizedFrom = fromDirectory.toAbsolutePath().normalize()
            val normalizedTarget = target.toAbsolutePath().normalize()
            if (normalizedFrom.root != normalizedTarget.root) return null

            normalizedFrom.relativize(normalizedTarget).toString().replace('\\', '/').takeIf {
                it.isNotBlank()
            }
        } catch (_: InvalidPathException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
