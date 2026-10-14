package com.antlr4plus.previewEditor

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile

internal class GeneratedPreviewBackedTextEditor(
    private val backingFile: VirtualFile,
    private val previewFile: VirtualFile,
    private val delegate: TextEditor,
) : TextEditor by delegate {

    private val documentManager = FileDocumentManager.getInstance()
    private val backingDocument =
        requireNotNull(documentManager.getDocument(backingFile)) {
            "Missing backing document for ${backingFile.path}"
        }
    private val previewDocument =
        requireNotNull(documentManager.getDocument(previewFile)) {
            "Missing preview document for ${previewFile.name}"
        }

    private var disposed = false
    private var previewToBackingDepth = 0
    private var backingToPreviewDepth = 0
    private val listenerLifetime = Disposer.newDisposable("ANTLR4+ generated preview document listeners")

    private val previewListener =
        object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (disposed || backingToPreviewDepth > 0 || IntentionPreviewUtils.isIntentionPreviewActive()) return
                mirrorPreviewChangeToBacking(event)
            }
        }

    private val backingListener =
        object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (disposed || previewToBackingDepth > 0 || IntentionPreviewUtils.isIntentionPreviewActive()) return
                mirrorBackingChangeToPreview(event)
            }
        }

    override fun getState(level: FileEditorStateLevel): FileEditorState = delegate.getState(level)

    override fun setState(state: FileEditorState, exactState: Boolean) {
        delegate.setState(state, exactState)
    }

    init {
        previewDocument.addDocumentListener(previewListener, listenerLifetime)
        backingDocument.addDocumentListener(backingListener, listenerLifetime)
    }

    override fun isModified(): Boolean = documentManager.isDocumentUnsaved(backingDocument)

    override fun getFile(): VirtualFile = backingFile

    override fun getFilesToRefresh(): List<VirtualFile> = delegate.filesToRefresh

    override fun getTabActions(): ActionGroup? = delegate.tabActions

    override fun isValid(): Boolean = !disposed && backingFile.isValid && delegate.isValid

    override fun selectNotify() {
        delegate.selectNotify()
    }

    override fun deselectNotify() {
        delegate.deselectNotify()
    }

    override fun getBackgroundHighlighter(): BackgroundEditorHighlighter? = delegate.backgroundHighlighter

    override fun getCurrentLocation(): FileEditorLocation? = delegate.currentLocation

    override fun getStructureViewBuilder(): StructureViewBuilder? = delegate.structureViewBuilder

    override fun dispose() {
        if (disposed) return
        disposed = true

        Disposer.dispose(listenerLifetime)
        TextEditorProvider.getInstance().disposeEditor(delegate)
    }

    private fun mirrorPreviewChangeToBacking(event: DocumentEvent) {
        previewToBackingDepth++
        try {
            applyEventToDocument(event, backingDocument)
        } finally {
            previewToBackingDepth--
        }
    }

    private fun mirrorBackingChangeToPreview(event: DocumentEvent) {
        backingToPreviewDepth++
        try {
            applyEventToDocument(event, previewDocument)
        } finally {
            backingToPreviewDepth--
        }
    }

    private fun applyEventToDocument(event: DocumentEvent, targetDocument: Document) {
        val offset = event.offset.coerceIn(0, targetDocument.textLength)
        val targetEndOffset = (event.offset + event.oldLength).coerceIn(offset, targetDocument.textLength)
        val newText = event.newFragment.toString()

        val update = {
            if (event.offset > targetDocument.textLength) {
                @Suppress("UsePropertyAccessSyntax") targetDocument.setText(event.document.text)
            } else {
                targetDocument.replaceString(offset, targetEndOffset, newText)
            }
        }

        if (ApplicationManager.getApplication().isWriteAccessAllowed) {
            update()
        } else {
            ApplicationManager.getApplication().runWriteAction(update)
        }
    }
}
