package com.antlr4plus.dotG4Support.lineMarker

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandlerBase
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GeneratedActionGotoDeclarationHandler : GotoDeclarationHandlerBase() {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor,
    ): Array<PsiElement>? {
        val project = sourceElement?.project ?: editor.project ?: return null
        val sourcePsiFile = resolveSourcePsiFile(project, editor, sourceElement) ?: return null
        val lookupContext =
            GeneratedActionEditorContextSupport.resolveLookupContext(
                editor = editor,
                sourcePsiFile = sourcePsiFile,
                offset = offset,
                sourceElement = sourceElement,
            ) ?: return null

        return when (lookupContext.sourceVFile.extension?.lowercase()) {
            "g4" -> {
                val resolved =
                    lookupContext.candidateOffsets.firstNotNullOfOrNull { candidateOffset ->
                        GeneratedActionNavigationSupport.resolveNavigation(
                            project = project,
                            grammarVFile = lookupContext.sourceVFile,
                            grammarText = lookupContext.sourceText,
                            grammarOffset = candidateOffset,
                        )
                    } ?: return null

                val target =
                    GeneratedActionNavigationSupport.resolveTargetElement(
                        project,
                        resolved.map.generatedFile,
                        resolved.entry,
                    ) ?: return null
                arrayOf(target)
            }
            "java",
            "kt" -> {
                val resolved =
                    lookupContext.candidateOffsets.firstNotNullOfOrNull { candidateOffset ->
                        GeneratedActionNavigationSupport.resolveNavigationFromGenerated(
                            project = project,
                            editor = editor,
                            generatedVFile = lookupContext.sourceVFile,
                            generatedOffset = candidateOffset,
                        )
                    } ?: return null

                val target =
                    GeneratedActionNavigationSupport.resolveGrammarTargetElement(
                        project = project,
                        grammarFile = resolved.grammarFile,
                        startOffset = resolved.grammarStartOffset,
                        endOffsetExclusive = resolved.grammarEndOffsetExclusive,
                    ) ?: return null
                arrayOf(target)
            }
            else -> null
        }
    }

    override fun getGotoDeclarationTarget(
        sourceElement: PsiElement?,
        editor: Editor,
    ): PsiElement? {
        val offset = sourceElement?.textRange?.startOffset ?: editor.caretModel.offset
        return getGotoDeclarationTargets(sourceElement, offset, editor)?.singleOrNull()
    }

    private fun resolveSourcePsiFile(
        project: com.intellij.openapi.project.Project,
        editor: Editor,
        sourceElement: PsiElement?,
    ): PsiFile? = sourceElement?.containingFile ?: PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
}
