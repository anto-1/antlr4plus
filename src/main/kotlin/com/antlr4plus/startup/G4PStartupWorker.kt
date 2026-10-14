package com.antlr4plus.startup

import com.antlr4plus.compose.ComposeFallbackLoader
import com.antlr4plus.filebinding.FileBindingService
import com.antlr4plus.onboarding.OnboardingDialog
import com.antlr4plus.onboarding.OnboardingManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.ExtensionNotApplicableException
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class G4PStartupWorker : ProjectActivity {
    private val logger = thisLogger()

    init {
        if (
            ApplicationManager.getApplication().isHeadlessEnvironment ||
                ApplicationManager.getApplication().isUnitTestMode
        ) {
            throw ExtensionNotApplicableException.create()
        }
    }

    override suspend fun execute(project: Project) {
        // Project selection can create LiveSettings before the tool window initializes Compose.
        ComposeFallbackLoader.ensureComposeAvailable()

        logger.info("G4PStartupWorker: execute called for project ${project.name}")

        val previewService = project.service<com.antlr4plus.previewService.PreviewService>()
        if (!previewService.isEnabled) {
            logger.info("G4PStartupWorker: Plugin is OFF, skipping startup processing")
            return
        }

        // Populate binding decorations asynchronously so the decorator can read cached data.
        project.service<FileBindingService>().refreshAllDecorations()

        if (!OnboardingManager.hasCompletedOnboarding()) {
            logger.info("G4PStartupWorker: Showing onboarding dialog (first run)")
            invokeLater {
                OnboardingDialog.showIfNeeded(project)
            }
        }

        logger.info("G4PStartupWorker: No auto-preview at startup")
    }
}
