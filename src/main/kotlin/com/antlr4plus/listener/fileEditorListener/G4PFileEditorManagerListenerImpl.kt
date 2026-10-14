package com.antlr4plus.listener.fileEditorListener

import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener

class G4PFileEditorManagerListenerImpl : FileEditorManagerListener {

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val project = event.manager.project
        val previewService = project.service<PreviewService>()
        if (!previewService.isEnabled) return
        // The lexer color button focuses this input to display its highlights.
        val activeInputFile = previewService.state.g4pInputFile
        if (activeInputFile != null && event.newFile == activeInputFile) return
        previewService.tokenTimelinePanel?.clearHighlightingState()
    }
}
