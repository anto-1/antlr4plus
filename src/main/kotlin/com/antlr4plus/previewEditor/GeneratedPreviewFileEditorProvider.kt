package com.antlr4plus.previewEditor

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

internal class GeneratedPreviewFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        GeneratedPreviewEditorSupport.isManagedPreviewFile(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val backingDocument =
            FileDocumentManager.getInstance().getDocument(file)
                ?: return TextEditorProvider.getInstance().createEditor(project, file)

        val previewFile =
            GeneratedPreviewEditorSupport.createSyntheticPreviewFile(
                backingFile = file,
                text = backingDocument.immutableCharSequence,
            )
        val delegate =
            TextEditorProvider.getInstance().createEditor(project, previewFile)
                as? com.intellij.openapi.fileEditor.TextEditor
                ?: return TextEditorProvider.getInstance().createEditor(project, file)

        return GeneratedPreviewBackedTextEditor(
            backingFile = file,
            previewFile = previewFile,
            delegate = delegate,
        )
    }

    override fun disposeEditor(editor: FileEditor) {
        editor.dispose()
    }

    override fun getEditorTypeId(): String = "antlr4plus.generated.preview.text.editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
