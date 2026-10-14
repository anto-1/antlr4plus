package com.antlr4plus.dotG4Support.completion

import com.antlr4plus.dotG4Support.language.FileRoot
import com.antlr4plus.dotG4Support.psi.DelegateGrammarNode
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/** Suggests grammar imports from .g4 files in the same directory. */
class ImportCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        resultSet: CompletionResultSet,
    ) {
        val position = parameters.position
        PsiTreeUtil.getParentOfType(position, DelegateGrammarNode::class.java) ?: return

        val containingFile = parameters.originalFile
        val containingDirectory = containingFile.containingDirectory ?: return

        val caseInsensitiveResultSet = resultSet.caseInsensitive()

        containingDirectory.children
            .asSequence()
            .filterIsInstance<FileRoot>()
            .map { it.name }
            .filter { it != containingFile.name }
            .map { it.removeSuffix(".g4") }
            .forEach { grammarName ->
                caseInsensitiveResultSet.addElement(LookupElementBuilder.create(grammarName))
            }
    }
}
