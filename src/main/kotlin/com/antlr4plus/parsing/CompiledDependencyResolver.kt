package com.antlr4plus.parsing

import com.antlr4plus.parsing.CompiledParsingService.ClassFileSearchResult
import com.antlr4plus.parsing.CompiledParsingService.DependencyContext
import com.antlr4plus.previewService.PreviewService
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns dependency choices and serializes their resolution dialogs across previews. */
internal class CompiledDependencyResolver(private val log: Logger) {
    private data class ConflictResolution(
        val filePath: String,
        val contentHash: String,
    )

    /** Choices keyed by input folder, bound grammar and class; cached files are checked by hash. */
    private val conflictResolutionCache = ConcurrentHashMap<String, ConflictResolution>()

    private fun cacheKey(g4pFolder: File, boundGrammarPath: String?, className: String): String {
        val grammarPart = boundGrammarPath ?: ""
        return "${g4pFolder.absolutePath}:$grammarPart:$className"
    }

    private fun bindingKey(g4pFolderPath: String, boundGrammarPath: String): String = "$g4pFolderPath:$boundGrammarPath"

    /** Serializes conflict dialogs across concurrent previews. */
    private val dialogMutex = Mutex()

    /** Suppresses repeated class conflicts during one dependency-resolution session. */
    private val resolvedClassesInSession = ConcurrentHashMap.newKeySet<String>()

    /** Folders eligible for relative-path decorations after conflict checking. */
    private val foldersWithConfirmedNoConflicts = ConcurrentHashMap.newKeySet<String>()

    private val resolvedConflictBindings = ConcurrentHashMap.newKeySet<String>()

    /**
     * Finds Java/Kotlin sources near the grammar, then through project indexes and disk fallback. Prompts for ambiguous
     * matches; scopes choices to the input folder and bound grammar.
     */
    suspend fun findClassFiles(
        classNames: Set<String>,
        startDir: File,
        projectBasePath: File?,
        indicator: ProgressIndicator?,
        dependencyContext: DependencyContext? = null,
        previewService: PreviewService? = null,
        scopeFolder: File = startDir,
        boundGrammarPath: String? = null,
    ): ClassFileSearchResult {
        if (classNames.isEmpty()) return ClassFileSearchResult.Success(emptyMap())

        val allMatches =
            CompiledSourceSearch.findCandidates(
                classNames = classNames,
                startDir = startDir,
                projectBasePath = projectBasePath,
                indicator = indicator,
                project = previewService?.project,
                log = log,
            )

        val result = mutableMapOf<String, File>()
        val conflicts = mutableMapOf<String, List<File>>()

        for ((className, files) in allMatches) {
            val uniqueFiles = files.distinctBy { it.absolutePath }
            if (uniqueFiles.size == 1) {
                result[className] = uniqueFiles.first()
            } else {
                val scopedKey = cacheKey(scopeFolder, boundGrammarPath, className)
                val cached = conflictResolutionCache[scopedKey]
                if (cached != null) {
                    val cachedFile = uniqueFiles.find { it.absolutePath == cached.filePath }
                    if (cachedFile != null) {
                        val currentHash = computeFileHash(cachedFile)
                        if (currentHash == cached.contentHash) {
                            log.info("Using cached choice for $className: ${cached.filePath}")
                            result[className] = cachedFile
                            continue
                        }
                    }
                    conflictResolutionCache.remove(scopedKey)
                }

                if (className in resolvedClassesInSession) {
                    log.info("Class $className already resolved in session, using first file")
                    result[className] = uniqueFiles.first()
                    continue
                }

                conflicts[className] = uniqueFiles
            }
        }

        if (conflicts.isNotEmpty()) {
            previewService?.let {
                withContext(Dispatchers.Main) {
                    ProjectView.getInstance(it.project).refresh()
                }
            }

            val resolved =
                promptUserToResolveAllConflicts(
                    conflicts,
                    scopeFolder,
                    dependencyContext,
                    previewService,
                    boundGrammarPath,
                ) ?: return ClassFileSearchResult.UserCancelled(conflicts.keys.first())
            foldersWithConfirmedNoConflicts.add(scopeFolder.absolutePath)
            previewService?.let {
                withContext(Dispatchers.Main) {
                    ProjectView.getInstance(it.project).refresh()
                }
            }
            result.putAll(resolved)
            return ClassFileSearchResult.Success(result, true)
        }

        return ClassFileSearchResult.Success(result)
    }

    /** Returns selected files for all conflicts, or null on cancellation. */
    private suspend fun promptUserToResolveAllConflicts(
        conflicts: Map<String, List<File>>,
        scopeFolder: File,
        context: DependencyContext?,
        previewService: PreviewService?,
        boundGrammarPath: String? = null,
    ): Map<String, File>? {
        if (conflicts.isEmpty()) return emptyMap()

        val grammarName = context?.generatedClass ?: "grammar"
        val conflictCount = conflicts.size

        val processingId = previewService?.statusOwnerProcessingId()
        var resolutionUiPublished = false

        try {
            if (previewService != null && processingId != null) {
                val resolveClickedFuture = java.util.concurrent.CompletableFuture<Unit>()

                withContext(Dispatchers.Main) {
                    coroutineContext.ensureActive()
                    if (!previewService.isCurrentProcessing(processingId)) {
                        throw kotlinx.coroutines.CancellationException(
                            "Preview request was superseded",
                        )
                    }
                    val message =
                        if (conflictCount == 1) {
                            "Multiple ${conflicts.keys.first()} files found.\nResolution Required."
                        } else {
                            "$conflictCount dependency conflicts found.\nResolution Required."
                        }
                    resolutionUiPublished =
                        previewService.beginDependencyResolution(processingId, message) {
                            resolveClickedFuture.complete(Unit)
                        }
                    if (!resolutionUiPublished) {
                        throw kotlinx.coroutines.CancellationException(
                            "Preview request was superseded",
                        )
                    }
                }

                suspendCancellableCoroutine { cont ->
                    resolveClickedFuture.whenComplete { _, error ->
                        if (error != null) {
                            cont.resumeWithException(error)
                        } else {
                            cont.resume(Unit)
                        }
                    }
                    cont.invokeOnCancellation {
                        resolveClickedFuture.complete(Unit)
                    }
                }
            }

            currentCoroutineContext().ensureActive()

            var result: Map<String, File>? = null

            dialogMutex.withLock {
                currentCoroutineContext().ensureActive()

                withContext(Dispatchers.Main) {
                    coroutineContext.ensureActive()

                    val selections =
                        showDependencyConflictDialog(
                            conflicts,
                            scopeFolder,
                            grammarName,
                            conflictCount,
                        )
                    if (selections != null) {
                        val resolved = mutableMapOf<String, File>()
                        for ((className, selectedFile) in selections) {
                            resolved[className] = selectedFile
                            val scopedKey = cacheKey(scopeFolder, boundGrammarPath, className)
                            val fileHash = computeFileHash(selectedFile)
                            conflictResolutionCache[scopedKey] =
                                ConflictResolution(
                                    filePath = selectedFile.absolutePath,
                                    contentHash = fileHash,
                                )
                            resolvedClassesInSession.add(className)
                            log.info(
                                "User selected ${selectedFile.absolutePath} for class $className",
                            )
                        }
                        markBindingResolved(scopeFolder.absolutePath, boundGrammarPath)
                        result = resolved
                    }
                }

                currentCoroutineContext().ensureActive()
            }

            return result
        } finally {
            if (previewService != null && processingId != null && resolutionUiPublished) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (!previewService.project.isDisposed) {
                        previewService.completeDependencyResolution(processingId)
                    }
                }
            }
        }
    }

    /** Clears dependency choices and their project-tree decorations. */
    fun clearAllSessionState() {
        conflictResolutionCache.clear()
        resolvedClassesInSession.clear()
        foldersWithConfirmedNoConflicts.clear()
        resolvedConflictBindings.clear()
        log.info("Cleared all dependency resolution session state")
    }

    private fun normalizePath(path: String): String = path.replace("\\", "/").trimEnd('/')

    fun hasConfirmedNoConflictsForFolder(folderPath: String): Boolean {
        val normalizedPath = normalizePath(folderPath)
        return foldersWithConfirmedNoConflicts.any { normalizePath(it) == normalizedPath }
    }

    /** Uses an absolute input-folder path and a project-relative bound grammar path. */
    fun hasResolvedConflictsForBinding(g4pFolderPath: String, boundGrammarPath: String?): Boolean {
        if (boundGrammarPath == null) return false
        val key = bindingKey(g4pFolderPath, boundGrammarPath)
        return resolvedConflictBindings.contains(key)
    }

    /** Resets choices for every input in this folder bound to the given grammar. */
    fun clearConflictResolutionForBinding(g4pFolderPath: String, boundGrammarPath: String) {
        val keyPrefix = bindingKey(g4pFolderPath, boundGrammarPath)

        resolvedConflictBindings.remove(keyPrefix)

        val keysToRemove = conflictResolutionCache.keys.filter { it.startsWith("$keyPrefix:") }
        keysToRemove.forEach { conflictResolutionCache.remove(it) }

        foldersWithConfirmedNoConflicts.remove(g4pFolderPath)

        log.info(
            "Cleared conflict resolution for binding: $keyPrefix (removed ${keysToRemove.size} cache entries)",
        )
    }

    private fun markBindingResolved(g4pFolderPath: String, boundGrammarPath: String?) {
        if (boundGrammarPath == null) return
        resolvedConflictBindings.add(bindingKey(g4pFolderPath, boundGrammarPath))
    }

    private fun computeFileHash(file: File): String =
        try {
            file
                .inputStream()
                .buffered()
                .use(
                    com.antlr4plus.parsing.security.GeneratedFileTrustStore::computeHash,
                )
        } catch (e: Exception) {
            log.warn("Failed to compute hash for ${file.name}: ${e.message}")
            ""
        }

    fun clearCachedChoices() = conflictResolutionCache.clear()

    fun clearResolvedClasses() = resolvedClassesInSession.clear()
}
