package com.antlr4plus.listener.editorFactoryListener

import com.antlr4plus.dotG4Support.actionInlay.EmbeddedCodeRegionHighlighter
import com.antlr4plus.dotG4Support.actionInlay.GeneratedActionRegionHighlighter
import com.antlr4plus.dotG4Support.lineMarker.GeneratedActionEditorContextSupport
import com.antlr4plus.listener.G4pEditorMouseListener
import com.antlr4plus.listener.GeneratedActionCtrlClickMouseListener
import com.antlr4plus.listener.documentListener.DocumentListenerImpl
import com.antlr4plus.listener.documentListener.GrammarDocumentListener
import com.antlr4plus.parsing.actionMap.ActionLocationMapService
import com.antlr4plus.parsing.actionMap.GeneratedSyncPreviewRefreshSupport
import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent

class EditorFactoryListenerImpl : EditorFactoryListener {

    private val editorDisposables = mutableMapOf<Editor, Disposable>()
    private val g4pMouseListenersMap = mutableMapOf<Editor, G4pEditorMouseListener>()
    private val generatedActionCtrlClickListeners = mutableMapOf<Editor, GeneratedActionCtrlClickMouseListener>()
    private val documentBindings = mutableMapOf<DocumentBindingKey, DocumentBinding>()

    private data class DocumentBindingKey(
        val project: Project,
        val document: Document,
        val role: String,
    )

    private data class DocumentBinding(
        val disposable: Disposable,
        var refCount: Int,
    )

    override fun editorCreated(event: EditorFactoryEvent) {
        event.editor.let { editor ->
            val virtualFile: VirtualFile? = FileDocumentManager.getInstance().getFile(editor.document)
            val project = editor.project ?: return@let
            var generatedActionCtrlClickInstalled = false

            // Attach while disabled too, so existing editors work when the plugin is enabled.

            val editorDisposable = Disposer.newDisposable("EditorListeners:${editor.hashCode()}")
            editorDisposables[editor] = editorDisposable

            when (virtualFile?.extension) {
                "g4p" -> {
                    acquireDocumentBinding(project, editor.document, "g4p-input-listener") { disposable ->
                        val listener = DocumentListenerImpl(project)
                        Disposer.register(disposable, listener)
                        editor.document.addDocumentListener(listener, disposable)
                    }

                    val mouseListener = G4pEditorMouseListener(project)
                    editor.addEditorMouseListener(mouseListener)
                    editor.addEditorMouseMotionListener(mouseListener)
                    g4pMouseListenersMap[editor] = mouseListener

                    // Parser input may resemble a script; suppress the shebang run icon.
                    com.antlr4plus.dotG4PSupport.lineMarker.G4PRunLineMarkerSuppressor.install(
                        editor,
                        editorDisposable,
                    )
                }
                "g4" -> {
                    // Generated editors need the normal Java/Kotlin click handlers.
                    val mouseListener = GeneratedActionCtrlClickMouseListener()
                    editor.addEditorMouseListener(mouseListener)
                    generatedActionCtrlClickListeners[editor] = mouseListener
                    generatedActionCtrlClickInstalled = true

                    acquireDocumentBinding(project, editor.document, "g4-document-listeners") { disposable ->
                        val grammarListener = GrammarDocumentListener(project)
                        Disposer.register(disposable, grammarListener)
                        editor.document.addDocumentListener(grammarListener, disposable)
                    }

                    acquireDocumentBinding(project, editor.document, "g4-embedded-highlight") { disposable ->
                        EmbeddedCodeRegionHighlighter(project, editor.document).install(disposable)
                    }

                    val focusListener =
                        object : FocusAdapter() {
                            override fun focusGained(event: FocusEvent?) {
                                if (project.isDisposed || editor.isDisposed) return
                                val grammarFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
                                val previewService = project.getService(PreviewService::class.java) ?: return
                                if (!previewService.isEnabled || previewService.isAutoRefreshPaused) return

                                // Binding scans and VFS lookups can access disk; keep them off the
                                // EDT.
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    if (project.isDisposed || editor.isDisposed) return@executeOnPooledThread
                                    val refreshTarget =
                                        GeneratedSyncPreviewRefreshSupport.resolvePendingRefreshTarget(
                                            project,
                                            grammarFile,
                                        )

                                    ApplicationManager.getApplication().invokeLater {
                                        if (project.isDisposed || editor.isDisposed) return@invokeLater
                                        val mapService =
                                            project.getService(ActionLocationMapService::class.java)
                                                ?: return@invokeLater
                                        val grammarIoFile = java.io.File(grammarFile.path)

                                        if (refreshTarget == null) {
                                            if (
                                                mapService.peekPendingGeneratedSync(
                                                    grammarIoFile,
                                                ) != null
                                            ) {
                                                mapService.clearPendingGeneratedSync(grammarIoFile)
                                            }
                                            return@invokeLater
                                        }

                                        FileDocumentManager.getInstance().saveDocument(editor.document)
                                        mapService.clearPendingGeneratedSync(grammarIoFile)
                                        previewService.launchProcessing(
                                            refreshTarget,
                                            forceRefresh = true,
                                        )
                                    }
                                }
                            }
                        }
                    editor.contentComponent.addFocusListener(focusListener)
                    Disposer.register(editorDisposable) {
                        editor.contentComponent.removeFocusListener(focusListener)
                    }
                }
                "java" -> {
                    acquireDocumentBinding(
                        project,
                        editor.document,
                        "generated-action-highlight",
                    ) { disposable ->
                        GeneratedActionRegionHighlighter(project, editor.document).install(disposable)
                    }
                }
                "kt" -> {
                    acquireDocumentBinding(
                        project,
                        editor.document,
                        "generated-action-highlight",
                    ) { disposable ->
                        GeneratedActionRegionHighlighter(project, editor.document).install(disposable)
                    }
                }
            }

            if (
                !generatedActionCtrlClickInstalled && GeneratedActionEditorContextSupport.isGrammarEditorContext(editor)
            ) {
                val mouseListener = GeneratedActionCtrlClickMouseListener()
                editor.addEditorMouseListener(mouseListener)
                generatedActionCtrlClickListeners[editor] = mouseListener
            }
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor: Editor = event.editor
        val project = editor.project
        val extension = FileDocumentManager.getInstance().getFile(editor.document)?.extension?.lowercase()

        editorDisposables.remove(editor)?.let { Disposer.dispose(it) }

        if (project != null) {
            when (extension) {
                "g4p" -> {
                    releaseDocumentBinding(project, editor.document, "g4p-input-listener")
                }
                "g4" -> {
                    releaseDocumentBinding(project, editor.document, "g4-document-listeners")
                    releaseDocumentBinding(project, editor.document, "g4-embedded-highlight")
                }
                "java" -> {
                    releaseDocumentBinding(project, editor.document, "java-document-listeners")
                    releaseDocumentBinding(project, editor.document, "generated-action-highlight")
                }
                "kt" -> {
                    releaseDocumentBinding(project, editor.document, "kt-document-listeners")
                    releaseDocumentBinding(project, editor.document, "generated-action-highlight")
                }
            }
        }

        // Mouse listeners are not managed by the disposable.
        g4pMouseListenersMap.remove(editor)?.let { mouseListener ->
            editor.removeEditorMouseListener(mouseListener)
            editor.removeEditorMouseMotionListener(mouseListener)
        }
        generatedActionCtrlClickListeners.remove(editor)?.let { mouseListener ->
            editor.removeEditorMouseListener(mouseListener)
        }
    }

    private fun acquireDocumentBinding(
        project: Project,
        document: Document,
        role: String,
        install: (Disposable) -> Unit,
    ) {
        val key = DocumentBindingKey(project, document, role)
        val existing = documentBindings[key]
        if (existing != null) {
            existing.refCount++
            return
        }

        val disposable = Disposer.newDisposable("DocumentBinding:$role:${document.hashCode()}")
        install(disposable)
        documentBindings[key] = DocumentBinding(disposable = disposable, refCount = 1)
    }

    private fun releaseDocumentBinding(
        project: Project,
        document: Document,
        role: String,
    ) {
        val key = DocumentBindingKey(project, document, role)
        val binding = documentBindings[key] ?: return
        binding.refCount--
        if (binding.refCount > 0) return

        documentBindings.remove(key)
        Disposer.dispose(binding.disposable)
    }
}
