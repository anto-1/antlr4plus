package com.antlr4plus.dotG4Support.lineMarker

import com.antlr4plus.previewEditor.GeneratedPreviewEditorSupport
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

internal object GeneratedActionEditorContextSupport {

    data class LookupContext(
        val sourceVFile: VirtualFile,
        val sourceText: CharSequence,
        val candidateOffsets: List<Int>,
    )

    fun isGrammarEditorContext(editor: Editor): Boolean {
        val project = editor.project ?: return false
        val psiFile =
            com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return false
        val sourceVFile = resolveSourceVirtualFile(project, editor, psiFile) ?: return false
        return sourceVFile.extension?.lowercase() == "g4"
    }

    fun resolveLookupContext(
        editor: Editor,
        sourcePsiFile: PsiFile,
        offset: Int,
        sourceElement: PsiElement? = null,
    ): LookupContext? {
        val project = editor.project ?: return null
        val sourceVFile = resolveSourceVirtualFile(project, editor, sourcePsiFile) ?: return null
        val injectedManager = InjectedLanguageManager.getInstance(project)
        val isInjected = injectedManager.isInjectedFragment(sourcePsiFile)

        if (!isInjected) {
            return LookupContext(
                sourceVFile = sourceVFile,
                sourceText = editor.document.charsSequence,
                candidateOffsets =
                    computeCandidateOffsets(
                        sourcePsiFile = sourcePsiFile,
                        document = editor.document,
                        offset = offset,
                        sourceElement = sourceElement,
                    ),
            )
        }

        val hostDocument =
            (editor.document as? DocumentWindow)?.delegate
                ?: FileDocumentManager.getInstance().getDocument(sourceVFile)
                ?: return null

        return LookupContext(
            sourceVFile = sourceVFile,
            sourceText = hostDocument.charsSequence,
            candidateOffsets =
                computeInjectedHostOffsets(
                    project = project,
                    sourcePsiFile = sourcePsiFile,
                    injectedDocument = editor.document,
                    offset = offset,
                    sourceElement = sourceElement,
                    hostDocument = hostDocument,
                ),
        )
    }

    private fun computeInjectedHostOffsets(
        project: com.intellij.openapi.project.Project,
        sourcePsiFile: PsiFile,
        injectedDocument: Document,
        offset: Int,
        sourceElement: PsiElement?,
        hostDocument: Document,
    ): List<Int> {
        val injectedCandidates =
            computeCandidateOffsets(
                sourcePsiFile = sourcePsiFile,
                document = injectedDocument,
                offset = offset,
                sourceElement = sourceElement,
            )
        val injectedManager = InjectedLanguageManager.getInstance(project)
        val maxOffset = hostDocument.textLength.coerceAtLeast(0)

        return injectedCandidates
            .mapNotNull { candidate ->
                runCatching {
                    when (injectedDocument) {
                        is DocumentWindow -> injectedDocument.injectedToHost(candidate)
                        else -> injectedManager.injectedToHost(sourcePsiFile, candidate)
                    }
                }
                    .getOrNull()
            }
            .map { candidate ->
                candidate.coerceIn(0, maxOffset)
            }
            .distinct()
    }

    private fun computeCandidateOffsets(
        sourcePsiFile: PsiFile,
        document: Document,
        offset: Int,
        sourceElement: PsiElement?,
    ): List<Int> {
        val adjustedOffset = TargetElementUtil.adjustOffset(sourcePsiFile, document, offset)
        val maxOffset = document.textLength.coerceAtLeast(0)
        val element = sourceElement ?: sourcePsiFile.findElementAt(adjustedOffset)

        return buildList {
            add(offset)
            add(adjustedOffset)
            element?.textRange?.let { range ->
                add(range.startOffset)
                if (range.endOffset > range.startOffset) {
                    add(range.endOffset - 1)
                }
            }
        }
            .map { candidate ->
                candidate.coerceIn(0, maxOffset)
            }
            .distinct()
    }

    fun resolveSourceVirtualFile(
        project: com.intellij.openapi.project.Project,
        editor: Editor,
        sourcePsiFile: PsiFile,
    ): VirtualFile? {
        val injectedManager = InjectedLanguageManager.getInstance(project)
        val topLevelPsiFile = injectedManager.getTopLevelFile(sourcePsiFile)
        val documentFile = FileDocumentManager.getInstance().getFile(editor.document)
        val hostDocumentFile =
            (editor.document as? DocumentWindow)?.delegate?.let {
                FileDocumentManager.getInstance().getFile(it)
            }

        return sequenceOf(
                hostDocumentFile,
                GeneratedPreviewEditorSupport.resolveBackingFile(hostDocumentFile),
                documentFile,
                GeneratedPreviewEditorSupport.resolveBackingFile(documentFile),
                sourcePsiFile.virtualFile,
                GeneratedPreviewEditorSupport.resolveBackingFile(sourcePsiFile.virtualFile),
                sourcePsiFile.originalFile.virtualFile,
                GeneratedPreviewEditorSupport.resolveBackingFile(
                    sourcePsiFile.originalFile.virtualFile,
                ),
                topLevelPsiFile.virtualFile,
                GeneratedPreviewEditorSupport.resolveBackingFile(topLevelPsiFile.virtualFile),
                topLevelPsiFile.originalFile.virtualFile,
                GeneratedPreviewEditorSupport.resolveBackingFile(
                    topLevelPsiFile.originalFile.virtualFile,
                ),
            )
            .filterNotNull()
            .firstOrNull()
    }
}
