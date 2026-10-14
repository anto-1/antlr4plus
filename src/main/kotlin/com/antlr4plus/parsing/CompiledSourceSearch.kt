package com.antlr4plus.parsing

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

/** Finds support sources through nearby directories, project indexes and a disk fallback. */
internal object CompiledSourceSearch {

    private val SKIP_DIRS =
        setOf(
            "build",
            "out",
            "target",
            "bin",
            ".gradle",
            ".idea",
            "node_modules",
            ".git",
            "gen",
            "generated",
            "examples",
            "additional-examples",
            "example",
            "test",
            "tests",
            "testdata",
            "test-data",
            "testcases",
            "test-cases",
        )

    /** The fallback also searches examples, tests and generated-source directories. */
    private val DISK_WALK_SKIP_DIRS =
        setOf(
            ".git",
            ".gradle",
            ".idea",
            ".hg",
            ".svn",
            "build",
            "out",
            "target",
            "bin",
            "node_modules",
            ".m2",
            ".cache",
            "__pycache__",
            "venv",
            ".venv",
        )

    /** Searches exact filenames with a depth limit and canonical-path cycle detection. */
    fun walkProjectForExactNames(
        root: File,
        targetFileNames: Set<String>,
        maxDepth: Int = 25,
    ): List<File> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        if (targetFileNames.isEmpty()) return emptyList()

        val results = mutableListOf<File>()
        val visited = mutableSetOf<String>()

        fun recurse(dir: File, depth: Int) {
            if (depth > maxDepth) return
            if (dir.name in DISK_WALK_SKIP_DIRS) return
            val canon =
                try {
                    dir.canonicalPath
                } catch (_: Exception) {
                    return
                }
            if (canon in visited) return
            visited.add(canon)

            val entries = dir.listFiles() ?: return
            for (e in entries) {
                if (e.isFile) {
                    if (e.name in targetFileNames) {
                        results.add(e)
                    }
                } else if (e.isDirectory) {
                    recurse(e, depth + 1)
                }
            }
        }

        recurse(root, 0)
        return results
    }

    /** Merges index or disk findings into the matches, deduplicating canonical paths. */
    private fun addIndexFindingsToResults(
        findings: List<Pair<String, File>>,
        label: String,
        allMatches: MutableMap<String, MutableList<File>>,
        log: Logger,
    ) {
        for ((className, file) in findings) {
            val existing = allMatches.getOrPut(className) { mutableListOf() }
            val existingPaths =
                existing
                    .map {
                        try {
                            it.canonicalPath
                        } catch (_: Exception) {
                            it.absolutePath
                        }
                    }
                    .toSet()
            val canonical =
                try {
                    file.canonicalPath
                } catch (_: Exception) {
                    file.absolutePath
                }
            if (canonical in existingPaths) continue

            existing.add(file)
            log.info("Found class file for $className via $label: ${file.absolutePath}")
        }
    }

    /** Queries scopes in order and deduplicates source paths under one read action. */
    private fun findIndexedSources(
        classNames: Set<String>,
        project: Project,
        log: Logger,
    ): List<Pair<String, File>> = runReadAction {
        val findings = mutableListOf<Pair<String, File>>()
        val seenPaths = mutableSetOf<String>()

        val scopes: List<Pair<String, GlobalSearchScope>> =
            listOf(
                "projectScope" to GlobalSearchScope.projectScope(project),
                "contentScope" to com.intellij.psi.search.ProjectScope.getContentScope(project),
                "allScope" to GlobalSearchScope.allScope(project),
            )

        for ((scopeName, scope) in scopes) {
            for (className in classNames) {
                for (ext in listOf(".java", ".kt")) {
                    val fileName = "$className$ext"
                    val vfiles = FilenameIndex.getVirtualFilesByName(fileName, scope)
                    for (vf in vfiles) {
                        if (!vf.isValid || vf.isDirectory) continue
                        val ioFile = File(vf.path)
                        if (!ioFile.exists() || !ioFile.isFile) continue
                        val canonical =
                            try {
                                ioFile.canonicalPath
                            } catch (_: Exception) {
                                ioFile.absolutePath
                            }
                        if (canonical in seenPaths) continue
                        seenPaths.add(canonical)
                        findings.add(className to ioFile)
                        log.info("Index hit ($scopeName): $fileName -> ${ioFile.absolutePath}")
                    }
                }
            }
            val foundClassNames = findings.map { it.first }.toSet()
            if (foundClassNames.containsAll(classNames)) break
        }
        findings
    }

    fun isWithinProjectScope(directory: File, projectRoot: File): Boolean =
        try {
            directory.canonicalFile.toPath().startsWith(projectRoot.canonicalFile.toPath())
        } catch (_: Exception) {
            false
        }

    /** Collects candidate sources in search order; the caller resolves ambiguous matches. */
    fun findCandidates(
        classNames: Set<String>,
        startDir: File,
        projectBasePath: File?,
        indicator: ProgressIndicator?,
        project: Project?,
        log: Logger,
    ): Map<String, List<File>> {
        val allMatches = mutableMapOf<String, MutableList<File>>()
        val targetFileNames = classNames.flatMap { listOf("$it.java", "$it.kt") }.toSet()
        val visitedDirs = mutableSetOf<String>()

        fun searchDirectory(dir: File, depth: Int = 0) {
            if (depth > 15) return
            if (!dir.exists() || !dir.isDirectory) return
            if (dir.name in SKIP_DIRS) return
            if (!visitedDirs.add(dir.absolutePath)) return

            val entries = dir.listFiles() ?: return

            for (entry in entries) {
                if (entry.isFile && entry.name in targetFileNames) {
                    val className = entry.nameWithoutExtension
                    allMatches.getOrPut(className) { mutableListOf() }.add(entry)
                    log.info("Found class file for $className: ${entry.absolutePath}")
                } else if (entry.isDirectory && entry.name !in SKIP_DIRS) {
                    searchDirectory(entry, depth + 1)
                }
            }
        }

        indicator?.text = "Searching for classes in ${startDir.name}..."
        searchDirectory(startDir)

        var currentDir: File? = startDir.parentFile

        while (currentDir != null) {
            if (projectBasePath != null && currentDir.absolutePath == projectBasePath.absolutePath) {
                log.info(
                    "Stopping flood-fill search at project root: ${projectBasePath.absolutePath}",
                )
                break
            }

            if (projectBasePath != null && !isWithinProjectScope(currentDir, projectBasePath)) {
                break
            }

            indicator?.text = "Searching for classes in ${currentDir.name}..."
            searchDirectory(currentDir)

            currentDir = currentDir.parentFile
        }

        // Project indexes cover locations skipped by the nearby-directory search.
        if (project != null) {
            indicator?.text = "Searching project index..."
            val indexFindings: List<Pair<String, File>> =
                try {
                    findIndexedSources(classNames, project, log)
                } catch (e: Exception) {
                    log.warn(
                        "FilenameIndex lookup failed, relying on directory walk and disk fallback: ${e.message}",
                    )
                    emptyList()
                }

            if (indexFindings.isNotEmpty()) {
                addIndexFindingsToResults(
                    findings = indexFindings,
                    label = "(project index)",
                    allMatches = allMatches,
                    log = log,
                )
            }
        }

        // Search disk for unresolved names that may be absent from the project index.
        run {
            val resolvedSoFar = allMatches.keys.toSet()
            val stillMissing = classNames - resolvedSoFar
            if (stillMissing.isEmpty()) return@run
            if (projectBasePath == null || !projectBasePath.exists() || !projectBasePath.isDirectory) return@run

            indicator?.text = "Walking project tree for ${stillMissing.size} class(es)..."
            log.info(
                "Phase 4 disk walk for ${stillMissing.size} unresolved class(es): $stillMissing",
            )

            val targetNames = stillMissing.flatMap { listOf("$it.java", "$it.kt") }.toSet()
            val diskFindings = walkProjectForExactNames(projectBasePath, targetNames)

            if (diskFindings.isNotEmpty()) {
                val pairs = diskFindings.map { it.nameWithoutExtension to it }
                addIndexFindingsToResults(
                    findings = pairs,
                    label = "(project tree walk)",
                    allMatches = allMatches,
                    log = log,
                )
            }
        }

        return allMatches
    }
}
