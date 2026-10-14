package com.antlr4plus.dotG4Support.completion

import com.antlr4plus.dotG4Support.psi.GrammarSpecNode
import com.antlr4plus.dotG4Support.psi.LexerRuleSpecNode
import com.antlr4plus.dotG4Support.psi.ParserRuleSpecNode
import com.antlr4plus.dotG4Support.psi.RuleSpecNode
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiElementFilter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/** Suggests lexer rules in lexer contexts, and both rule types in parser contexts. */
class RuleCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        resultSet: CompletionResultSet,
    ) {
        val position = parameters.position
        val file = parameters.originalFile

        val grammarSpec = PsiTreeUtil.getChildOfType(file, GrammarSpecNode::class.java) ?: return

        val inLexerRule = isInsideLexerRule(position)

        val caseInsensitiveResultSet = resultSet.caseInsensitive()

        if (!inLexerRule) {
            collectRules(grammarSpec, ParserRuleSpecNode::class.java).forEach { ruleName ->
                caseInsensitiveResultSet.addElement(
                    LookupElementBuilder.create(ruleName).withIcon(AllIcons.Nodes.Method).withTypeText("parser rule"),
                )
            }
        }

        collectRules(grammarSpec, LexerRuleSpecNode::class.java).forEach { ruleName ->
            caseInsensitiveResultSet.addElement(
                LookupElementBuilder.create(ruleName).withIcon(AllIcons.Nodes.Field).withTypeText("lexer rule"),
            )
        }
    }

    private fun isInsideLexerRule(position: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(position, LexerRuleSpecNode::class.java) != null

    private fun collectRules(
        grammarSpec: GrammarSpecNode,
        ruleClass: Class<out RuleSpecNode>,
    ): List<String> {
        val filter = PsiElementFilter { element ->
            ruleClass.isInstance(element)
        }

        return PsiTreeUtil.collectElements(grammarSpec, filter)
            .filterIsInstance<RuleSpecNode>()
            .mapNotNull { it.nameIdentifier?.text }
            .distinct()
    }
}
