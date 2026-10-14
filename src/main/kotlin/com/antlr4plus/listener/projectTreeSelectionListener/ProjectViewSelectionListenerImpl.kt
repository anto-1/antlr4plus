package com.antlr4plus.listener.projectTreeSelectionListener

import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.util.rethrowIfControlFlow
import com.intellij.ide.DataManager
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewSelectionListener
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.util.ui.tree.TreeUtil
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ProjectViewSelectionListenerImpl : ProjectViewSelectionListener {
    private var debounceJob: Job? = null
    private val debounceDelayMs = 300L

    @Volatile private var lastSelectedPath: String? = null

    override fun onChanged() {
        val focusOwner = IdeFocusManager.getGlobalInstance().focusOwner ?: return
        val dataContext = DataManager.getInstance().getDataContext(focusOwner)
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return

        val previewService = project.service<PreviewService>()

        if (!previewService.isEnabled) return

        // ProjectView.refresh() can repeat the selection; do not invalidate a running preview.
        val selectedFile =
            try {
                val projectView = ProjectView.getInstance(project)
                val paths = projectView.currentProjectViewPane?.selectionPaths
                if (paths != null && paths.size == 1) {
                    val node = paths[0].lastPathComponent
                    val userObject = TreeUtil.getUserObject(node)
                    (userObject as? ProjectViewNode<*>)?.virtualFile
                } else null
            } catch (failure: Exception) {
                rethrowIfControlFlow(failure)
                null
            }
        val currentSelection = selectedFile?.path

        if (currentSelection != null && currentSelection == lastSelectedPath) return
        lastSelectedPath = currentSelection

        debounceJob?.cancel()
        val pendingGeneration = previewService.invalidateCurrentProcessing()

        // Capture Swing selection before entering the project-owned coroutine.
        debounceJob =
            previewService.cs.launch {
                delay(debounceDelayMs.milliseconds)

                if (selectedFile != null && isG4pFile(selectedFile)) {
                    logger.info(
                        "Selected G4P file for preview: ${selectedFile.name} (${selectedFile.path})",
                    )
                    ApplicationManager.getApplication().invokeLater {
                        if (
                            !project.isDisposed &&
                                previewService.isCurrentProcessing(pendingGeneration) &&
                                lastSelectedPath == selectedFile.path
                        ) {
                            previewService.launchProcessing(selectedFile)
                        }
                    }
                }
            }
    }

    private fun isG4pFile(file: VirtualFile) =
        !file.isDirectory && G4P_EXTENSION.equals(file.extension, ignoreCase = true)

    companion object {
        private const val G4P_EXTENSION = "g4p"
        private val logger = Logger.getInstance(ProjectViewSelectionListenerImpl::class.java)
    }
}
