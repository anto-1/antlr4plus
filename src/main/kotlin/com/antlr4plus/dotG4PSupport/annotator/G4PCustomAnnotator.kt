package com.antlr4plus.dotG4PSupport.annotator

import com.antlr4plus.error.syntaxErrorEditorRange
import com.antlr4plus.parsing.InputTextCoordinates
import com.antlr4plus.previewService.PreviewService
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class G4PCustomAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile || element.fileType.defaultExtension != "g4p") {
            return
        }
        val project = element.project
        val previewService = project.service<PreviewService>()
        if (!previewService.isEnabled) return
        val virtualFile = element.virtualFile ?: return

        val parsingResult = previewService.getParsingResultForG4PFile(virtualFile) ?: return
        val syntaxErrors = parsingResult.syntaxErrorListener?.syntaxErrors ?: return
        val document = element.viewProvider.document ?: return
        val coordinates = InputTextCoordinates(document.text)
        syntaxErrors.forEach { error ->
            val range = syntaxErrorEditorRange(error, coordinates) ?: return@forEach
            holder.newAnnotation(HighlightSeverity.ERROR, error.message ?: "Syntax error").range(range).create()
        }
    }
}
