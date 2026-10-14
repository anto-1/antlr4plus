package com.antlr4plus.dotG4Support.completion

import com.antlr4plus.dotG4Support.psi.DelegateGrammarNode
import com.antlr4plus.dotG4Support.psi.LexerRuleRefNode
import com.antlr4plus.dotG4Support.psi.OptionsSpecNode
import com.antlr4plus.dotG4Support.psi.ParserRuleRefNode
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns

class CompletionContributor : CompletionContributor() {

    init {
        val lexerRuleImportPattern =
            PlatformPatterns.psiElement(LexerRuleRefNode::class.java)
                .withSuperParent(2, DelegateGrammarNode::class.java)

        val parserRuleImportPattern =
            PlatformPatterns.psiElement(ParserRuleRefNode::class.java)
                .withSuperParent(2, DelegateGrammarNode::class.java)

        val importProvider = ImportCompletionProvider()
        extend(CompletionType.BASIC, lexerRuleImportPattern, importProvider)
        extend(CompletionType.BASIC, parserRuleImportPattern, importProvider)

        // Candidate types depend on the containing rule, not the reference token's case.
        val ruleProvider = RuleCompletionProvider()

        val lexerRuleRefPattern =
            PlatformPatterns.psiElement(LexerRuleRefNode::class.java)
                .andNot(
                    PlatformPatterns.psiElement().withSuperParent(2, DelegateGrammarNode::class.java),
                )

        val parserRuleRefPattern =
            PlatformPatterns.psiElement(ParserRuleRefNode::class.java)
                .andNot(
                    PlatformPatterns.psiElement().withSuperParent(2, DelegateGrammarNode::class.java),
                )

        extend(CompletionType.BASIC, lexerRuleRefPattern, ruleProvider)
        extend(CompletionType.BASIC, parserRuleRefPattern, ruleProvider)

        // Option PSI wrappers vary in depth, so match by ancestry.
        val insideOptionsBlock =
            PlatformPatterns.psiElement().inside(PlatformPatterns.psiElement(OptionsSpecNode::class.java))

        extend(CompletionType.BASIC, insideOptionsBlock, OptionsCompletionProvider())
    }
}
