package com.antlr4plus.dotG4Support.lineMarker

import com.antlr4plus.parsing.actionMap.ActionLocationMap
import com.antlr4plus.util.escapeHtml
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.util.Function as IjFunction
import java.awt.event.MouseEvent
import java.io.File
import java.util.function.Supplier

/** Shows navigation for embedded blocks with a current source-to-generated mapping. */
class JumpToGeneratedActionLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null

        val containingFile = element.containingFile ?: return null
        val vfile = containingFile.virtualFile ?: containingFile.originalFile.virtualFile
        if (vfile?.extension?.lowercase() != "g4") return null
        if (element.text != "{") return null
        val grammarVFile = vfile

        val project = element.project
        val bodyOffset = element.textRange.startOffset + 1
        val resolved =
            GeneratedActionNavigationSupport.resolveNavigation(
                project = project,
                grammarVFile = grammarVFile,
                grammarText = containingFile.text,
                grammarOffset = bodyOffset,
            ) ?: return null

        val tooltip = buildTooltip(resolved.entry, resolved.map.generatedFile)
        val nav =
            GutterIconNavigationHandler<PsiElement> { _: MouseEvent?, _: PsiElement? ->
                navigateToGeneratedAction(project, resolved.map.generatedFile, resolved.entry)
            }

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.EditSource,
            IjFunction<PsiElement, String> { tooltip },
            nav,
            GutterIconRenderer.Alignment.RIGHT,
            Supplier { tooltip },
        )
    }

    private fun buildTooltip(entry: ActionLocationMap.Entry, generatedFile: File): String {
        val scopeText = if (entry.scope != null) " in <b>${escapeHtml(entry.scope)}</b>" else ""
        return buildString {
            append("<html>")
            append("<b>${escapeHtml(entry.kind)}</b>$scopeText<br>")
            append("Open generated region in <code>${escapeHtml(generatedFile.name)}</code><br>")
            append("<i>Jump to the emitted code for this grammar block.</i>")
            append("</html>")
        }
    }

    private fun navigateToGeneratedAction(
        project: com.intellij.openapi.project.Project,
        generatedFile: File,
        entry: ActionLocationMap.Entry,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val vf =
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(generatedFile) ?: return@executeOnPooledThread
            val offset =
                ApplicationManager.getApplication().runReadAction<Int> {
                    GeneratedActionNavigationSupport.computeNavigationOffset(
                        vf,
                        generatedFile,
                        entry,
                    )
                }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || !vf.isValid) return@invokeLater
                val descriptor = OpenFileDescriptor(project, vf, offset)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            }
        }
    }
}
