package com.antlr4plus.projectActivity

import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Services are lazily loaded - invoke here to trigger creation
        project.getService(PreviewService::class.java)
    }
}
