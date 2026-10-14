package com.antlr4plus.filebinding

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.concurrency.SequentialTaskExecutor
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.jetbrains.annotations.TestOnly

/** Cached presentation data for .g4p project-tree decoration. */
data class G4pDecorationInfo(
    val isBound: Boolean,
    val isBroken: Boolean,
    val simpleDecoration: String,
    val relativePathDecoration: String,
    val boundGrammarPath: String?,
    val grammarFile: VirtualFile?,
)

/**
 * Persists project-relative grammar paths in .g4p user attributes and updates bindings on VFS moves. Binding and
 * resolution methods perform synchronous filesystem access. UI decorators should use [getDecorationInfo].
 */
@Service(Service.Level.PROJECT)
class FileBindingService(val project: Project) : Disposable {

    private val logger = Logger.getInstance(FileBindingService::class.java)
    private val pathChangeExecutor =
        SequentialTaskExecutor.createSequentialApplicationPoolExecutor(
            "ANTLR4+ binding path changes",
        )

    private val decorationCache = ConcurrentHashMap<String, G4pDecorationInfo>()

    private val defaultDecoration =
        G4pDecorationInfo(
            isBound = false,
            isBroken = false,
            simpleDecoration = " ",
            relativePathDecoration = " ",
            boundGrammarPath = null,
            grammarFile = null,
        )

    init {
        val listener =
            object : AsyncFileListener {
                override fun prepareChange(
                    events: List<VFileEvent>,
                ): AsyncFileListener.ChangeApplier? {
                    val pathChanges = buildList {
                        events.filterIsInstance<VFileDeleteEvent>().forEach { event ->
                            if (!event.file.isInLocalFileSystem) return@forEach
                            addAll(capturePathChanges(event.file, event.file.path))
                        }
                        events.filterIsInstance<VFileMoveEvent>().forEach { event ->
                            if (!event.file.isInLocalFileSystem) return@forEach
                            addAll(capturePathChanges(event.file, event.oldPath))
                        }
                        events
                            .filterIsInstance<VFilePropertyChangeEvent>()
                            .filter {
                                it.propertyName == VirtualFile.PROP_NAME && it.file.isInLocalFileSystem
                            }
                            .forEach { event ->
                                val oldName = event.oldValue as? String ?: return@forEach
                                val parent = event.file.parent ?: return@forEach
                                addAll(capturePathChanges(event.file, "${parent.path}/$oldName"))
                            }
                    }
                    if (pathChanges.isEmpty()) return null

                    return object : AsyncFileListener.ChangeApplier {
                        override fun afterVfsChange() {
                            try {
                                pathChangeExecutor.execute {
                                    if (project.isDisposed) return@execute
                                    val grammarChanges = pathChanges.filter { it.wasGrammar }
                                    val bindingMoves =
                                        grammarChanges
                                            .mapNotNull { change ->
                                                val oldPath = change.oldProjectPath ?: return@mapNotNull null
                                                val newPath =
                                                    change.file.takeIf { it.isValid }?.let(::getProjectRelativePath)
                                                        ?: return@mapNotNull null
                                                (oldPath to newPath).takeIf { oldPath != newPath }
                                            }
                                            .toMap()

                                    if (bindingMoves.isNotEmpty()) {
                                        updateBindingsForGrammarMoves(bindingMoves)
                                    }

                                    if (grammarChanges.isNotEmpty()) {
                                        // Refresh even when a move leaves the project or changes
                                        // the grammar extension.
                                        refreshAllDecorationsSync()
                                        return@execute
                                    }

                                    var profileCacheChanged = false
                                    pathChanges
                                        .filter { it.wasProfile }
                                        .forEach { change ->
                                            decorationCache.remove(change.oldAbsolutePath)
                                            profileCacheChanged = true
                                            if (change.file.isValid && change.file.isG4pFile()) {
                                                refreshDecoration(change.file)
                                            }
                                        }
                                    if (profileCacheChanged) handleChange()
                                }
                            } catch (_: RejectedExecutionException) {
                                // Project disposal can race with a final VFS notification.
                            }
                        }
                    }
                }
            }
        VirtualFileManager.getInstance().addAsyncFileListener(listener, this)
    }

    override fun dispose() {
        decorationCache.clear()
        pathChangeExecutor.shutdownNow()
    }

    @TestOnly
    internal fun awaitPendingPathChangesForTest() {
        pathChangeExecutor.submit {}.get(10, TimeUnit.SECONDS)
    }

    private data class CapturedPathChange(
        val oldProjectPath: String?,
        val oldAbsolutePath: String,
        val file: VirtualFile,
        val wasGrammar: Boolean,
        val wasProfile: Boolean,
    )

    private fun capturePathChanges(
        root: VirtualFile,
        oldAbsoluteRoot: String,
    ): List<CapturedPathChange> {
        val oldProjectRoot =
            project.basePath?.let { basePath ->
                ProjectPathPolicy.relativize(Path.of(basePath), Path.of(oldAbsoluteRoot))
            } ?: return emptyList()
        val affectedFiles = mutableListOf<VirtualFile>()

        if (root.isDirectory) {
            VfsUtilCore.visitChildrenRecursively(
                root,
                object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (!file.isDirectory && (file.isGrammarFile() || file.isG4pFile())) {
                            affectedFiles.add(file)
                        }
                        return true
                    }
                },
            )
        } else if (root.isGrammarFile() || root.isG4pFile()) {
            affectedFiles.add(root)
        }

        return affectedFiles.mapNotNull { file ->
            val relativeToRoot =
                if (file == root) {
                    ""
                } else {
                    VfsUtilCore.getRelativePath(file, root, '/') ?: return@mapNotNull null
                }
            val suffix = relativeToRoot.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
            CapturedPathChange(
                oldProjectPath = "$oldProjectRoot$suffix",
                oldAbsolutePath = "$oldAbsoluteRoot$suffix",
                file = file,
                wasGrammar = file.isGrammarFile(),
                wasProfile = file.isG4pFile(),
            )
        }
    }

    private fun updateBindingsForGrammarMoves(grammarMoves: Map<String, String>) {
        if (grammarMoves.isEmpty()) return

        val projectRoot = getProjectRoot() ?: return
        var updated = 0

        VfsUtilCore.visitChildrenRecursively(
            projectRoot,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory && file.isG4pFile()) {
                        val boundPath = FileAttributeHelper.readGrammarBinding(file)
                        val newGrammarPath = grammarMoves[boundPath]
                        if (newGrammarPath != null) {
                            if (FileAttributeHelper.writeGrammarBinding(file, newGrammarPath)) {
                                logger.info(
                                    "Updated binding: ${file.name} -> $newGrammarPath (was $boundPath)",
                                )
                                updated++
                            }
                        }
                    }
                    return true
                }
            },
        )

        if (updated > 0) {
            logger.info(
                "Updated $updated binding(s) for ${grammarMoves.size} grammar path change(s)",
            )
        }
    }

    fun bind(g4pFile: VirtualFile, grammarFile: VirtualFile): Boolean {
        if (!g4pFile.isG4pFile()) {
            logger.warn("Cannot bind non-.g4p file: ${g4pFile.name}")
            return false
        }
        if (!grammarFile.isGrammarFile()) {
            logger.warn("Cannot bind to non-.g4 file: ${grammarFile.name}")
            return false
        }
        if (getProjectRelativePath(g4pFile) == null) {
            logger.warn("Cannot bind a profile outside the project: ${g4pFile.path}")
            return false
        }

        val relativePath =
            getProjectRelativePath(grammarFile)
                ?: run {
                    logger.warn("Cannot compute relative path for grammar: ${grammarFile.path}")
                    return false
                }

        val success = FileAttributeHelper.writeGrammarBinding(g4pFile, relativePath)
        if (success) {
            logger.info("Bound ${g4pFile.name} -> $relativePath")
            refreshDecoration(g4pFile)
            handleChange()
        } else {
            logger.warn("Failed to bind ${g4pFile.name} -> $relativePath")
        }
        return success
    }

    /** Binds files synchronously and refreshes once; false may indicate partial success. Call off the EDT. */
    fun setAll(g4pFiles: List<VirtualFile>, grammarFile: VirtualFile): Boolean {
        if (!grammarFile.isGrammarFile()) {
            logger.warn("Invalid grammar file: ${grammarFile.name}")
            return false
        }

        val relativePath =
            getProjectRelativePath(grammarFile)
                ?: run {
                    logger.warn("Cannot compute relative path for grammar: ${grammarFile.path}")
                    return false
                }

        val boundFiles = mutableListOf<VirtualFile>()
        for (g4p in g4pFiles) {
            if (!g4p.isG4pFile()) {
                logger.warn("Skipping non-.g4p file: ${g4p.name}")
                continue
            }
            if (getProjectRelativePath(g4p) == null) {
                logger.warn("Skipping profile outside the project: ${g4p.path}")
                continue
            }
            if (FileAttributeHelper.writeGrammarBinding(g4p, relativePath)) {
                boundFiles.add(g4p)
            }
        }

        if (boundFiles.isNotEmpty()) {
            logger.info("Bound ${boundFiles.size} files to ${grammarFile.name}")
            boundFiles.forEach { refreshDecoration(it) }
            handleChange()
        }
        return boundFiles.size == g4pFiles.size
    }

    fun unbind(g4pFile: VirtualFile): Boolean {
        val success = FileAttributeHelper.removeGrammarBinding(g4pFile)
        if (success) {
            logger.info("Unbound ${g4pFile.name}")
            refreshDecoration(g4pFile)
            handleChange()
        }
        return success
    }

    fun remove(g4pFile: VirtualFile): Boolean = unbind(g4pFile)

    fun unbindAll(g4pFiles: Collection<VirtualFile>): Boolean {
        var allSuccess = true
        val unboundFiles = mutableListOf<VirtualFile>()
        for (g4p in g4pFiles) {
            if (FileAttributeHelper.removeGrammarBinding(g4p)) {
                unboundFiles.add(g4p)
            } else {
                allSuccess = false
            }
        }
        if (unboundFiles.isNotEmpty()) {
            unboundFiles.forEach { refreshDecoration(it) }
            handleChange()
        }
        return allSuccess
    }

    fun getBoundGrammar(g4pFile: VirtualFile): VirtualFile? =
        FileAttributeHelper.readGrammarBinding(g4pFile)?.let { relativePath ->
            resolveProjectPath(relativePath)?.takeIf { it.isGrammarFile() }
        }

    fun isBound(g4pFile: VirtualFile): Boolean = FileAttributeHelper.hasGrammarBinding(g4pFile)

    /** A stored binding is broken when it cannot resolve to a valid grammar inside the project. */
    fun isBindingBroken(g4pFile: VirtualFile): Boolean {
        val relativePath = FileAttributeHelper.readGrammarBinding(g4pFile) ?: return false
        return resolveProjectPath(relativePath)?.isGrammarFile() != true
    }

    fun getBoundGrammarPath(g4pFile: VirtualFile): String? = FileAttributeHelper.readGrammarBinding(g4pFile)

    fun getProjectRoot(): VirtualFile? {
        @Suppress("DEPRECATION")
        return project.baseDir?.takeIf { it.isValid }
    }

    private fun getProjectRelativePath(file: VirtualFile): String? =
        project.basePath?.let { basePath ->
            ProjectPathPolicy.relativize(Path.of(basePath), file.toNioPath())
        }

    /** Resolves a project-relative path through the filesystem and VFS; call off the EDT. */
    private fun resolveProjectPath(relativePath: String): VirtualFile? {
        val projectRoot = getProjectRoot() ?: return null
        val resolved = ProjectPathPolicy.resolve(projectRoot.toNioPath(), relativePath) ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(resolved)
    }

    /** Cache-only lookup for UI decorators; returns the default until populated. */
    fun getDecorationInfo(g4pFile: VirtualFile): G4pDecorationInfo = decorationCache[g4pFile.path] ?: defaultDecoration

    fun refreshAllDecorations() {
        ProgressManager.getInstance()
            .run(
                object :
                    Task.Backgroundable(
                        project,
                        "ANTLR4+: Computing project tree decorations...",
                        false,
                    ) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        indicator.text2 = "Scanning for .g4p files..."

                        val projectRoot = getProjectRoot() ?: return

                        val g4pFiles = mutableListOf<VirtualFile>()
                        VfsUtilCore.visitChildrenRecursively(
                            projectRoot,
                            object : VirtualFileVisitor<Unit>() {
                                override fun visitFile(file: VirtualFile): Boolean {
                                    if (indicator.isCanceled) return false
                                    if (!file.isDirectory && file.isG4pFile()) {
                                        g4pFiles.add(file)
                                    }
                                    return true
                                }
                            },
                        )

                        indicator.isIndeterminate = false
                        val refreshed = mutableMapOf<String, G4pDecorationInfo>()
                        g4pFiles.forEachIndexed { index, file ->
                            if (indicator.isCanceled) return
                            indicator.fraction = index.toDouble() / g4pFiles.size
                            indicator.text2 = file.name

                            val info = computeDecorationInfo(file)
                            refreshed[file.path] = info
                        }

                        if (indicator.isCanceled) return
                        decorationCache.keys.removeIf { it !in refreshed }
                        decorationCache.putAll(refreshed)

                        handleChange()
                    }
                },
            )
    }

    /** Recomputes all decorations synchronously without a progress indicator; call off the EDT. */
    fun refreshAllDecorationsSync() {
        val projectRoot = getProjectRoot() ?: return
        val refreshed = mutableMapOf<String, G4pDecorationInfo>()

        VfsUtilCore.visitChildrenRecursively(
            projectRoot,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory && file.isG4pFile()) {
                        val info = computeDecorationInfo(file)
                        refreshed[file.path] = info
                    }
                    return true
                }
            },
        )

        decorationCache.keys.removeIf { it !in refreshed }
        decorationCache.putAll(refreshed)

        handleChange()
    }

    /** Recomputes one decoration synchronously; call off the EDT. */
    fun refreshDecoration(g4pFile: VirtualFile) {
        if (!g4pFile.isG4pFile()) return
        val info = computeDecorationInfo(g4pFile)
        decorationCache[g4pFile.path] = info
    }

    private fun computeDecorationInfo(g4pFile: VirtualFile): G4pDecorationInfo {
        val boundPath =
            FileAttributeHelper.readGrammarBinding(g4pFile)
                ?: return G4pDecorationInfo(
                    isBound = false,
                    isBroken = false,
                    simpleDecoration = " ",
                    relativePathDecoration = " ",
                    boundGrammarPath = null,
                    grammarFile = null,
                )

        val grammarFile = resolveProjectPath(boundPath)?.takeIf { it.isGrammarFile() }

        if (grammarFile == null) {
            val filename = boundPath.substringAfterLast('/')
            val brokenDecoration = " ⚠ $filename (missing)"
            return G4pDecorationInfo(
                isBound = true,
                isBroken = true,
                simpleDecoration = brokenDecoration,
                relativePathDecoration = brokenDecoration,
                boundGrammarPath = boundPath,
                grammarFile = null,
            )
        }

        val simpleDecoration = " 🔗 ${grammarFile.name}"
        val relativePathDecoration = computeRelativePathDecoration(g4pFile, grammarFile)

        return G4pDecorationInfo(
            isBound = true,
            isBroken = false,
            simpleDecoration = simpleDecoration,
            relativePathDecoration = relativePathDecoration,
            boundGrammarPath = boundPath,
            grammarFile = grammarFile,
        )
    }

    private fun computeRelativePathDecoration(
        g4pFile: VirtualFile,
        grammarFile: VirtualFile,
    ): String {
        val g4pParent = g4pFile.parent?.path ?: return " 🔗 ${grammarFile.name}"
        val displayPath =
            ProjectPathPolicy.relativeDisplayPath(
                Path.of(g4pParent),
                Path.of(grammarFile.path),
            ) ?: grammarFile.name

        return " 🔗 $displayPath"
    }

    private fun handleChange() {
        ApplicationManager.getApplication()
            .invokeLater(
                {
                    ProjectView.getInstance(project)?.refresh()
                },
                ModalityState.nonModal(),
            )
    }

    companion object {
        fun VirtualFile.isGrammarFile(): Boolean {
            if (!isValid || isDirectory) return false
            val ext = extension ?: return false
            return ext.equals("g4", ignoreCase = true)
        }

        fun VirtualFile.isG4pFile(): Boolean {
            if (!isValid || isDirectory) return false
            val ext = extension ?: return false
            return ext.equals("g4p", ignoreCase = true)
        }
    }
}
