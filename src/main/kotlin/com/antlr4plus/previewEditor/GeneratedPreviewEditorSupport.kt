package com.antlr4plus.previewEditor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.io.File

internal object GeneratedPreviewEditorSupport {

    val BACKING_FILE_KEY: Key<VirtualFile> = Key.create("antlr4plus.generated.preview.backing.file")

    fun createSyntheticPreviewFile(
        backingFile: VirtualFile,
        text: CharSequence,
    ): LightVirtualFile =
        LightVirtualFile(backingFile.name, backingFile.fileType, text).apply {
            charset = backingFile.charset
            putUserData(BACKING_FILE_KEY, backingFile)
        }

    fun resolveBackingFile(document: Document): VirtualFile? =
        FileDocumentManager.getInstance().getFile(document)?.let { file ->
            resolveBackingFile(file)
        }

    fun resolveBackingFile(file: VirtualFile?): VirtualFile? = file?.getUserData(BACKING_FILE_KEY) ?: file

    fun isSyntheticPreviewFile(file: VirtualFile?): Boolean = file?.getUserData(BACKING_FILE_KEY) != null

    fun resolveBackingIoFile(document: Document): File? = resolveBackingFile(document)?.let(::toIoFile)

    fun resolveBackingIoFile(file: VirtualFile?): File? = resolveBackingFile(file)?.let(::toIoFile)

    fun isManagedPreviewFile(file: VirtualFile?): Boolean {
        if (isSyntheticPreviewFile(file)) return false

        val backingFile = resolveBackingFile(file) ?: return false

        val extension = backingFile.extension?.lowercase() ?: return false
        if (extension != "java" && extension != "kt") return false

        val path = backingFile.path
        return looksLikePreviewPath(path)
    }

    private fun looksLikePreviewPath(path: String): Boolean =
        path.contains("/preview/") || path.contains("${File.separator}preview${File.separator}")

    private fun toIoFile(file: VirtualFile): File = File(file.path)
}
