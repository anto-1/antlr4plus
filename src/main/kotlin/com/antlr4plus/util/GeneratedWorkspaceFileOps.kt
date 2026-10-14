package com.antlr4plus.util

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Generated-workspace operations with symbolic-link checks and traversal that does not follow links. */
internal object GeneratedWorkspaceFileOps {
    fun isDirectory(path: Path): Boolean =
        isPathFreeOfSymbolicLinks(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

    fun isRegularFile(path: Path): Boolean =
        isPathFreeOfSymbolicLinks(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)

    fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    /** Creates [path] if absent, rejecting detected links and non-directory roots. */
    fun ensureDirectory(path: Path): Boolean {
        if (!isPathFreeOfSymbolicLinks(path)) return false
        if (exists(path)) return isDirectory(path)
        return runCatching {
                Files.createDirectories(path)
                isDirectory(path)
            }
            .getOrDefault(false)
    }

    /** Deletes a regular file or link after checking its parent path; absent paths count as success. */
    fun deleteFileOrLink(path: Path): Boolean {
        if (!isParentPathFreeOfSymbolicLinks(path)) return false
        if (!exists(path)) return true
        if (!isRegularFile(path) && !Files.isSymbolicLink(path)) return false
        return runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
    }

    /** Deletes a tree without following links encountered by the walker; a root link is deleted itself. */
    fun deleteTree(path: Path): Boolean {
        if (!isParentPathFreeOfSymbolicLinks(path)) return false
        if (!exists(path)) return true
        return runCatching {
                Files.walkFileTree(
                    path,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            Files.delete(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            exc: java.io.IOException,
                        ): FileVisitResult {
                            throw exc
                        }

                        override fun postVisitDirectory(
                            dir: Path,
                            exc: java.io.IOException?,
                        ): FileVisitResult {
                            if (exc != null) throw exc
                            Files.delete(dir)
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
                true
            }
            .getOrDefault(false)
    }

    fun isEmptyDirectory(path: Path): Boolean {
        if (!isDirectory(path)) return false
        return runCatching {
                Files.newDirectoryStream(path).use { entries -> !entries.iterator().hasNext() }
            }
            .getOrDefault(false)
    }

    /** Finds matching regular files, skipping symbolic links and linked directories. */
    fun findRegularFilesWithSuffix(root: Path, suffix: String): List<File> {
        if (!isDirectory(root)) return emptyList()
        return runCatching {
                val files = mutableListOf<File>()
                Files.walk(root).use { paths ->
                    paths.forEach { path ->
                        if (isRegularFile(path) && path.fileName.toString().endsWith(suffix)) {
                            files.add(path.toFile())
                        }
                    }
                }
                files.sortedBy { it.absolutePath }
            }
            .getOrDefault(emptyList())
    }

    /** Checks every existing path component because NOFOLLOW_LINKS covers only the final component. */
    private fun isPathFreeOfSymbolicLinks(path: Path): Boolean = runCatching {
        val absolutePath = path.toAbsolutePath().normalize()
        var current = absolutePath.root
        for (component in absolutePath) {
            current = current?.resolve(component) ?: component
            if (Files.isSymbolicLink(current)) return@runCatching false
        }
        true
    }
        .getOrDefault(false)

    private fun isParentPathFreeOfSymbolicLinks(path: Path): Boolean =
        path.toAbsolutePath().normalize().parent?.let(::isPathFreeOfSymbolicLinks) ?: true
}
