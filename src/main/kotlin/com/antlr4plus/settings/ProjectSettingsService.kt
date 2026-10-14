package com.antlr4plus.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.components.State
import com.intellij.openapi.project.Project

/** Settings shared by all preview inputs in the project. */
@Service(Service.Level.PROJECT)
@State(name = "PS1", storages = [Storage(".plgn/ps.xml")])
class ProjectSettingsService : SimplePersistentStateComponent<ProjectSettingsService.ProjectState>(ProjectState()) {

    class ProjectState : BaseState() {
        var colorizeTokens by property(true)
        var cosmeticTokenHighlight by property(true)
        var gutterTokenIndicator by property(true)
    }

    companion object {
        fun getInstance(project: Project): ProjectSettingsService = project.service<ProjectSettingsService>()
    }
}
