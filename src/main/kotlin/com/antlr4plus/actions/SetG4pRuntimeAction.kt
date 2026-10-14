package com.antlr4plus.actions

import com.antlr4plus.icons.Icons
import com.antlr4plus.parsing.KotlinRuntimeCompiler
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.settings.SettingsPersistenceService
import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager

/** Sets the preview runtime for selected .g4p files in the project tree. */
class SetG4pRuntimeActionGroup : DefaultActionGroup() {

    companion object {
        private const val TOOL_WINDOW_ID = "ANTLR4 Plus"
    }

    init {
        isPopup = true
        templatePresentation.text = "Set Preview Runtime"
        templatePresentation.description = "Set the preview runtime for selected .g4p files"
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        arrayOf(
            SetJavaRuntimeAction(),
            SetKotlinRuntimeAction(),
        )

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null || !toolWindow.isVisible) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val g4pFiles =
            files?.filter {
                !it.isDirectory && it.extension.equals("g4p", ignoreCase = true)
            } ?: emptyList()

        e.presentation.apply {
            isEnabledAndVisible = g4pFiles.isNotEmpty()
            text = if (g4pFiles.size > 1) "Set Preview Runtime (${g4pFiles.size} files)" else "Set Preview Runtime"
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class SetJavaRuntimeAction : AnAction("Java", "Set Java as preview runtime", Icons.JAVA_RUNTIME_16) {

    override fun actionPerformed(e: AnActionEvent) {
        setRuntime(e, "Java")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = hasG4pFiles(e)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class SetKotlinRuntimeAction : AnAction("Kotlin", "Set Kotlin as preview runtime", Icons.KOTLIN_RUNTIME_16) {

    override fun actionPerformed(e: AnActionEvent) {
        setRuntime(e, "Kotlin")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = hasG4pFiles(e)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private fun hasG4pFiles(e: AnActionEvent): Boolean {
    val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return false
    return files.any { !it.isDirectory && it.extension.equals("g4p", ignoreCase = true) }
}

private fun setRuntime(e: AnActionEvent, runtime: String) {
    val project = e.project ?: return
    val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return

    val g4pFiles = files.filter {
        !it.isDirectory && it.extension.equals("g4p", ignoreCase = true)
    }

    if (g4pFiles.isEmpty()) return

    val settingsService = project.service<SettingsPersistenceService>()
    val previewService = project.service<PreviewService>()
    var changedCount = 0
    var activeFileChanged = false

    for (file in g4pFiles) {
        val settings = settingsService.getSettings(file.path)
        if (settings.previewRuntime != runtime) {
            settings.previewRuntime = runtime
            changedCount++
            if (previewService.state.g4pInputFile?.path == file.path) {
                activeFileChanged = true
            }
        }
    }

    if (changedCount > 0) {
        settingsService.markModified()

        // Refresh runtime icons in the project tree.
        ProjectView.getInstance(project).refresh()

        // Warm up in the background to reduce the first Kotlin preview delay.
        if (runtime == "Kotlin" && !KotlinRuntimeCompiler.isWarmedUp()) {
            ApplicationManager.getApplication().executeOnPooledThread {
                if (KotlinRuntimeCompiler.isAvailable() && !KotlinRuntimeCompiler.isWarmedUp()) {
                    KotlinRuntimeCompiler.warmup()
                }
            }
        }

        if (activeFileChanged) {
            val activeG4pFile = previewService.state.g4pInputFile
            if (activeG4pFile != null) {
                previewService.state.liveSettings?.previewRuntime = runtime
                previewService.tokenTimelinePanel?.clearHighlightingState()
                previewService.launchProcessingAfterRuntimeChange(activeG4pFile, runtime)
            }
        }

        Notifications.Bus.notify(
            Notification(
                "ANTLR4Plus",
                "Preview Runtime Changed",
                "Set $runtime runtime for $changedCount file${if (changedCount > 1) "s" else ""}.",
                NotificationType.INFORMATION,
            ),
            project,
        )
    }
}
