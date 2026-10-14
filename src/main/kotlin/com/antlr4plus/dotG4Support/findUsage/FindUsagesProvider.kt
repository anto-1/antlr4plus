package com.antlr4plus.dotG4Support.findUsage

import com.antlr4plus.dotG4Support.psi.ChannelSpecNode
import com.antlr4plus.dotG4Support.psi.LexerRuleRefNode
import com.antlr4plus.dotG4Support.psi.LexerRuleSpecNode
import com.antlr4plus.dotG4Support.psi.ModeSpecNode
import com.antlr4plus.dotG4Support.psi.ParserRuleRefNode
import com.antlr4plus.dotG4Support.psi.ParserRuleSpecNode
import com.antlr4plus.dotG4Support.psi.RuleSpecNode
import com.antlr4plus.dotG4Support.psi.TokenSpecNode
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

class FindUsagesProvider : FindUsagesProvider {
    override fun canFindUsagesFor(psiElement: PsiElement) = psiElement is RuleSpecNode

    override fun getWordsScanner(): WordsScanner? = null

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String =
        when (element) {
            is ParserRuleSpecNode -> "parser rule"
            is LexerRuleSpecNode -> "lexer rule"
            is ModeSpecNode -> "mode"
            is TokenSpecNode -> "token"
            is ChannelSpecNode -> "channel"
            else -> "n/a"
        }

    override fun getDescriptiveName(element: PsiElement): String {
        val rule: PsiElement? =
            PsiTreeUtil.findChildOfAnyType(
                element,
                LexerRuleRefNode::class.java,
                ParserRuleRefNode::class.java,
            )
        if (rule != null) return rule.text
        return "n/a"
    }

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}
