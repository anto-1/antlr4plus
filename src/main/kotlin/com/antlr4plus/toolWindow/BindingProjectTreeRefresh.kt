package com.antlr4plus.toolWindow

import com.antlr4plus.filebinding.FileBindingService
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.nio.file.Path

internal fun refreshBindingProjectTree(
    project: Project,
    successfulG4pPaths: Collection<Path>,
    msg: String,
    onFinished: () -> Unit,
    updateStatus: (String) -> Unit,
) {
    if (project.isDisposed) return

    val total = successfulG4pPaths.size
    if (total == 0) {
        onFinished()
        updateStatus(msg)
        return
    }

    ProgressManager.getInstance()
        .run(
            object :
                Task.Backgroundable(
                    project,
                    "Syncing project tree ($total files)",
                    true,
                ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    val bindingService = project.getService(FileBindingService::class.java)
                    val localFs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()

                    indicator.text = "Refreshing directories..."
                    val parentDirs = successfulG4pPaths.mapNotNull { it.parent }.map { it.toFile() }.distinct()
                    indicator.text = "Refreshing ${parentDirs.size} directories..."

                    // Wait for parent-directory refresh before looking up renamed files for
                    // decorations.
                    localFs.refreshIoFiles(parentDirs, false, false, null)
                    indicator.fraction = 0.5

                    indicator.text = "Updating decorations..."
                    var processed = 0
                    for (nioPath in successfulG4pPaths) {
                        if (indicator.isCanceled) return
                        val vf = localFs.findFileByNioFile(nioPath)
                        if (vf != null) {
                            bindingService.refreshDecoration(vf)
                        }
                        processed++
                        if (processed % 1000 == 0 || processed == total) {
                            indicator.fraction = 0.5 + (processed.toDouble() / total) * 0.5
                            indicator.text2 = "$processed of $total files"
                        }
                    }
                }

                override fun onSuccess() {
                    if (!project.isDisposed) {
                        val projectView = com.intellij.ide.projectView.ProjectView.getInstance(project)
                        projectView.refresh()
                        projectView.currentProjectViewPane?.updateFromRoot(true)
                    }
                    onFinished()
                    updateStatus(msg)
                }

                override fun onCancel() {
                    if (!project.isDisposed) {
                        com.intellij.ide.projectView.ProjectView.getInstance(project).refresh()
                    }
                    onFinished()
                    updateStatus("$msg (sync cancelled)")
                }

                override fun onThrowable(error: Throwable) {
                    if (!project.isDisposed) {
                        com.intellij.ide.projectView.ProjectView.getInstance(project).refresh()
                    }
                    onFinished()
                    updateStatus("$msg (incomplete: ${error.message})")
                }
            },
        )
}
