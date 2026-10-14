package com.antlr4plus.parsing

import com.antlr4plus.parsing.security.GeneratedFileTrustStore
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.util.GeneratedWorkspaceFileOps
import com.antlr4plus.util.rethrowIfControlFlow
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Records generated-file hashes for the current project session and checks them before reuse. */
@Service(Service.Level.PROJECT)
class TrustedFilesManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(TrustedFilesManager::class.java)
    private val store by lazy { GeneratedFileTrustStore.getInstance(project) }

    init {
        project.messageBus
            .connect(this)
            .subscribe(
                PreviewService.PLUGIN_TOGGLE_TOPIC,
                object : PreviewService.PluginToggleListener {
                    override fun onPluginToggled(enabled: Boolean) {
                        if (!enabled) store.clear()
                    }
                },
            )
    }

    fun markAsGenerated(file: File) {
        if (!GeneratedWorkspaceFileOps.isRegularFile(file.toPath())) {
            log.warn(
                "Refusing to mark a missing, non-regular, or linked generated file: ${file.absolutePath}",
            )
            return
        }
        computeHash(file)?.let { store.markGenerated(file.absolutePath, it) }
    }

    fun markAsGenerated(vf: VirtualFile) {
        if (!GeneratedWorkspaceFileOps.isRegularFile(Path.of(vf.path))) {
            log.warn(
                "Refusing to mark a missing, non-regular, or linked generated file: ${vf.path}",
            )
            return
        }
        computeHash(vf)?.let { store.markGenerated(vf.path, it) }
    }

    fun markAsGenerated(files: List<File>) {
        files.forEach(::markAsGenerated)
    }

    fun isGeneratedByUs(file: File): Boolean {
        if (!GeneratedWorkspaceFileOps.isRegularFile(file.toPath())) return false
        val expectedHash = store.get(file.absolutePath) ?: return false
        return computeHash(file) == expectedHash
    }

    fun isGeneratedByUs(vf: VirtualFile): Boolean {
        if (!GeneratedWorkspaceFileOps.isRegularFile(Path.of(vf.path))) return false
        val expectedHash = store.get(vf.path) ?: return false
        return computeHash(vf) == expectedHash
    }

    /** Cached UI hint only; use [isGeneratedByUs] to verify current content. */
    fun isMarkedGeneratedCached(vf: VirtualFile): Boolean = store.getCached(vf.path) != null

    override fun dispose() = Unit

    sealed class ClassFileCheckResult {
        data object Valid : ClassFileCheckResult()

        data object NoFiles : ClassFileCheckResult()

        data class UnmarkedFiles(val files: List<String>) : ClassFileCheckResult()

        data class TamperedFiles(val files: List<String>) : ClassFileCheckResult()
    }

    /** Marks regular class files only when the directory inventory passes link checks. */
    fun markClassFilesAsGenerated(classDir: File): List<File> {
        val inventory = collectClassFileInventory(classDir) ?: return emptyList()
        markAsGenerated(inventory.classFiles)
        return inventory.classFiles
    }

    fun checkClassFiles(classDir: File): ClassFileCheckResult {
        if (!GeneratedWorkspaceFileOps.isDirectory(classDir.toPath())) return ClassFileCheckResult.NoFiles

        val inventory = collectClassFileInventory(classDir) ?: return ClassFileCheckResult.NoFiles
        if (inventory.unsafeEntries.isNotEmpty()) {
            return ClassFileCheckResult.UnmarkedFiles(inventory.unsafeEntries)
        }
        if (inventory.classFiles.isEmpty()) return ClassFileCheckResult.NoFiles

        val unmarked = mutableListOf<String>()
        val tampered = mutableListOf<String>()
        for (file in inventory.classFiles) {
            val displayPath = relativeDisplayPath(classDir, file.toPath())
            val expectedHash = store.get(file.absolutePath)
            when {
                expectedHash == null -> unmarked.add(displayPath)
                computeHash(file) != expectedHash -> tampered.add(displayPath)
            }
        }

        return when {
            tampered.isNotEmpty() -> ClassFileCheckResult.TamperedFiles(tampered)
            unmarked.isNotEmpty() -> ClassFileCheckResult.UnmarkedFiles(unmarked)
            else -> ClassFileCheckResult.Valid
        }
    }

    private data class ClassFileInventory(
        val classFiles: List<File>,
        val unsafeEntries: List<String>,
    )

    private fun collectClassFileInventory(classDir: File): ClassFileInventory? {
        val root = classDir.toPath().toAbsolutePath().normalize()
        if (!GeneratedWorkspaceFileOps.isDirectory(root)) return null
        val classFiles = mutableListOf<File>()
        val unsafeEntries = mutableListOf<String>()

        return try {
            Files.walk(root).use { paths ->
                paths.forEach { path ->
                    if (path == root) return@forEach
                    when {
                        Files.isSymbolicLink(path) -> unsafeEntries.add(relativeDisplayPath(classDir, path))
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                            path.fileName.toString().endsWith(".class") -> classFiles.add(path.toFile())
                    }
                }
            }
            ClassFileInventory(
                classFiles.sortedBy { it.absolutePath },
                unsafeEntries.sorted(),
            )
        } catch (failure: Exception) {
            rethrowIfControlFlow(failure)
            log.warn("Failed to inventory compiled classes below ${classDir.absolutePath}", failure)
            null
        }
    }

    private fun relativeDisplayPath(classDir: File, path: Path): String =
        try {
            classDir
                .toPath()
                .toAbsolutePath()
                .normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace(File.separatorChar, '/')
        } catch (_: IllegalArgumentException) {
            path.fileName?.toString() ?: path.toString()
        }

    data class SourceFileCollection(
        val generatedFiles: List<File>,
        val baseClassFiles: List<File>,
        val compiledClassFiles: List<File> = emptyList(),
        val missingSuperClasses: List<String> = emptyList(),
        val userCancelledClassName: String? = null,
    ) {
        val allFiles: List<File>
            get() = generatedFiles + baseClassFiles + compiledClassFiles

        val hasMissingSuperClasses: Boolean
            get() = missingSuperClasses.isNotEmpty()

        val wasUserCancelled: Boolean
            get() = userCancelledClassName != null
    }

    private fun computeHash(file: File): String? =
        try {
            file.inputStream().buffered().use(GeneratedFileTrustStore::computeHash)
        } catch (failure: Exception) {
            rethrowIfControlFlow(failure)
            log.warn("Failed to hash generated file: ${file.absolutePath}", failure)
            null
        }

    private fun computeHash(vf: VirtualFile): String? =
        try {
            vf.inputStream.buffered().use(GeneratedFileTrustStore::computeHash)
        } catch (failure: Exception) {
            rethrowIfControlFlow(failure)
            log.warn("Failed to hash generated file: ${vf.path}", failure)
            null
        }

    companion object {
        fun getInstance(project: Project): TrustedFilesManager = project.service()
    }
}
