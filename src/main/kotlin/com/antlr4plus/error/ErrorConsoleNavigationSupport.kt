package com.antlr4plus.error

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Keeps persistent-VFS lookup and recursive file searches off the Swing/Compose UI thread. */
internal fun navigateToResolvedFile(
    project: Project,
    line: Int,
    column: Int,
    resolve: () -> VirtualFile?,
) {
    ApplicationManager.getApplication().executeOnPooledThread {
        if (project.isDisposed) return@executeOnPooledThread
        val virtualFile = resolve() ?: return@executeOnPooledThread
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !virtualFile.isValid) return@invokeLater
            val descriptor = OpenFileDescriptor(project, virtualFile, line, column)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }
}
