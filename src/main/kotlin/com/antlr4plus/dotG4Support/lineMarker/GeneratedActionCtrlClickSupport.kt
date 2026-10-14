package com.antlr4plus.dotG4Support.lineMarker

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager

internal object GeneratedActionCtrlClickSupport {

    data class ResolvedTarget(
        val file: VirtualFile,
        val offset: Int,
    )

    fun resolveTarget(editor: Editor, offset: Int): ResolvedTarget? {
        val project = editor.project ?: return null
        val sourcePsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        val lookupContext =
            GeneratedActionEditorContextSupport.resolveLookupContext(editor, sourcePsiFile, offset) ?: return null

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

                val targetVFile =
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resolved.map.generatedFile) ?: return null

                ResolvedTarget(
                    file = targetVFile,
                    offset =
                        GeneratedActionNavigationSupport.computeNavigationOffset(
                            targetVFile,
                            resolved.map.generatedFile,
                            resolved.entry,
                        ),
                )
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

                val targetVFile =
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resolved.grammarFile) ?: return null

                ResolvedTarget(
                    file = targetVFile,
                    offset =
                        GeneratedActionNavigationSupport.computeGrammarNavigationOffset(
                            targetVFile,
                            resolved.grammarFile,
                            resolved.grammarStartOffset,
                            resolved.grammarEndOffsetExclusive,
                        ),
                )
            }
            else -> null
        }
    }
}
