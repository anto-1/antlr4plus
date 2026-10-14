package com.antlr4plus.error

import com.antlr4plus.parsing.InputTextCoordinates
import com.antlr4plus.previewService.PreviewService
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import org.antlr.intellij.adaptor.parser.SyntaxError

class G4PExternalAnnotator : ExternalAnnotator<VirtualFile, List<SyntaxError>?>(), DumbAware {
    override fun collectInformation(file: PsiFile): VirtualFile? {
        val vf = file.virtualFile?.takeIf { it.extension == "g4p" } ?: return null
        val previewService = file.project.service<PreviewService>()
        if (!previewService.isEnabled) return null
        return vf
    }

    override fun doAnnotate(file: VirtualFile?): List<SyntaxError>? {
        if (file == null) return null
        val project =
            com.intellij.openapi.project.ProjectLocator.getInstance().getProjectsForFile(file).firstOrNull()
                ?: return null
        val previewService = project.service<PreviewService>()
        if (!previewService.isEnabled) return null
        val parsingResult = previewService.getParsingResultForG4PFile(file) ?: return null
        return parsingResult.syntaxErrorListener?.syntaxErrors?.filterNotNull()
    }

    override fun apply(file: PsiFile, errors: List<SyntaxError>?, holder: AnnotationHolder) {
        val document = FileDocumentManager.getInstance().getDocument(file.virtualFile) ?: return
        val coordinates = InputTextCoordinates(document.text)
        errors?.forEach { error ->
            val range = syntaxErrorEditorRange(error, coordinates) ?: return@forEach
            holder.newAnnotation(HighlightSeverity.ERROR, error.message ?: "Syntax error").range(range).create()
        }
    }
}
