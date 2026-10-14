package com.antlr4plus.listener

import com.antlr4plus.actions.MyActionUtils
import com.antlr4plus.dotG4Support.actionInlay.EmbeddedCodeRegionHighlighter
import com.antlr4plus.dotG4Support.lineMarker.GeneratedActionCtrlClickSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import java.awt.event.MouseEvent

class GeneratedActionCtrlClickMouseListener : EditorMouseListener {
    override fun mousePressed(event: EditorMouseEvent) {
        handle(event)
    }

    private fun handle(event: EditorMouseEvent) {
        if (event.area != EditorMouseEventArea.EDITING_AREA || !event.isOverText) return

        val mouseEvent = event.mouseEvent
        if (mouseEvent.button != MouseEvent.BUTTON1) return
        if (!mouseEvent.isControlDown && !mouseEvent.isMetaDown) return

        val project = event.editor.project ?: return
        val offsets = buildList {
            add(MyActionUtils.getMouseOffset(mouseEvent, event.editor))
            add(event.offset)
        }
            .distinct()
            .filter { offset ->
                offset in 0 until event.editor.document.textLength
            }
        if (!isInsideEmbeddedCode(project, event.editor, offsets)) return

        // Claim the ctrl-click before IntelliJ's default handlers consume it,
        // then perform source-map/VFS resolution away from Swing's EDT.
        mouseEvent.consume()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed || event.editor.isDisposed) return@executeOnPooledThread
            val target =
                ApplicationManager.getApplication().runReadAction<
                    GeneratedActionCtrlClickSupport.ResolvedTarget?,
                > {
                    offsets.firstNotNullOfOrNull { offset ->
                        GeneratedActionCtrlClickSupport.resolveTarget(event.editor, offset)
                    }
                } ?: return@executeOnPooledThread

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || event.editor.isDisposed || !target.file.isValid) return@invokeLater
                FileEditorManager.getInstance(project)
                    .openTextEditor(
                        OpenFileDescriptor(project, target.file, target.offset),
                        true,
                    )
            }
        }
    }

    private fun isInsideEmbeddedCode(
        project: com.intellij.openapi.project.Project,
        editor: com.intellij.openapi.editor.Editor,
        offsets: List<Int>,
    ): Boolean {
        val markupModel = DocumentMarkupModel.forDocument(editor.document, project, false) ?: return false
        return markupModel.allHighlighters.any { highlighter ->
            highlighter.getUserData(EmbeddedCodeRegionHighlighter.OUR_HIGHLIGHTER_KEY) == true &&
                offsets.any { offset ->
                    offset in highlighter.startOffset until highlighter.endOffset
                }
        }
    }
}
