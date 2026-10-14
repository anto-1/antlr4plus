package com.antlr4plus.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

object EditorUtils {
    fun getOrFocusEditor(project: Project, file: VirtualFile): Editor? {
        val fileEditorManager = FileEditorManager.getInstance(project)

        // Complete the EDT file-open operation before querying the selected editor.
        if (ApplicationManager.getApplication().isDispatchThread) {
            fileEditorManager.openFile(file, true)
        } else {
            ApplicationManager.getApplication()
                .invokeAndWait(
                    {
                        fileEditorManager.openFile(file, true)
                    },
                    ModalityState.defaultModalityState(),
                )
        }

        val selectedEditor = fileEditorManager.getSelectedEditor(file)

        return (selectedEditor as? TextEditor)?.editor
    }

    fun getOrOpenEditorWithoutFocus(project: Project, file: VirtualFile): Editor? {
        val fileEditorManager = FileEditorManager.getInstance(project)

        // Complete the EDT file-open operation before querying the selected editor.
        if (ApplicationManager.getApplication().isDispatchThread) {
            fileEditorManager.openFile(file, false)
        } else {
            ApplicationManager.getApplication()
                .invokeAndWait(
                    {
                        fileEditorManager.openFile(file, false)
                    },
                    ModalityState.defaultModalityState(),
                )
        }

        val selectedEditor = fileEditorManager.getSelectedEditor(file)

        return (selectedEditor as? TextEditor)?.editor
    }

    /** Finds an existing text editor without opening a file, suitable for cleanup. */
    fun getOpenEditor(project: Project, file: VirtualFile): Editor? {
        val fileEditorManager = FileEditorManager.getInstance(project)
        return fileEditorManager.getEditors(file).filterIsInstance<TextEditor>().firstOrNull()?.editor
    }

    fun centerRangeInEditor(editor: Editor, startIndex: Int, stopIndex: Int) {
        val document = editor.document
        val middleOffset = (startIndex + stopIndex) / 2
        val clampedOffset = middleOffset.coerceIn(0, document.textLength - 1)
        val logicalPosition = editor.offsetToLogicalPosition(clampedOffset)
        editor.scrollingModel.scrollTo(logicalPosition, ScrollType.CENTER)
    }

    fun clearSelection(project: Project, file: VirtualFile) {
        getOrOpenEditorWithoutFocus(project, file)?.selectionModel?.removeSelection()
    }
}
