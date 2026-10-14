package com.antlr4plus.dotG4Support.references

import com.antlr4plus.dotG4Support.TokenTypes.RULE_ELEMENT_TYPES
import com.antlr4plus.dotG4Support.language.FileRoot
import com.antlr4plus.dotG4Support.psi.GrammarElementRefNode
import com.antlr4plus.dotG4Support.psi.GrammarSpecNode
import com.antlr4plus.dotG4Support.psi.LexerRuleSpecNode
import com.antlr4plus.dotG4Support.psi.MyPsiUtils
import com.antlr4plus.dotG4Support.psi.TokenSpecNode
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.plugin.parser.ANTLRv4Parser

object TokenVocabResolver {
    /** Resolves a tokenVocab option value to a grammar file. */
    @JvmStatic
    fun resolveTokenVocabFile(reference: PsiElement?): PsiFile? {
        reference ?: throw NullPointerException("Missing reference: PsiElement?")
        val optionValue =
            PsiTreeUtil.findFirstParent(
                reference,
                Condition { obj: PsiElement -> isOptionValue(obj) },
            )

        if (optionValue != null) {
            val option = optionValue.parent

            if (option != null) {
                val optionName = PsiTreeUtil.getDeepestFirst(option)

                if (optionName.text == "tokenVocab") {
                    val text = reference.text.trim('\'')
                    return findRelativeFile(text, reference.containingFile)
                }
            }
        }

        return null
    }

    @JvmStatic
    fun resolveInTokenVocab(reference: GrammarElementRefNode, ruleName: String?): PsiElement? {
        val tokenVocab = MyPsiUtils.findTokenVocabIfAny(reference.containingFile as FileRoot?) ?: return null
        val tokenVocabFile = findRelativeFile(tokenVocab, reference.containingFile) ?: return null
        val lexerGrammar = PsiTreeUtil.findChildOfType(tokenVocabFile, GrammarSpecNode::class.java)
        val node = MyPsiUtils.findSpecNode(lexerGrammar, ruleName)

        return when {
            // Fragments do not produce tokens for parser references.
            node is LexerRuleSpecNode && !node.isFragment -> node
            node is TokenSpecNode -> node
            else -> null
        }
    }

    private fun isOptionValue(el: PsiElement): Boolean {
        val node = el.node
        return node != null && node.elementType === RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_optionValue]
    }

    /** Finds a sibling grammar named baseName.g4. */
    @JvmStatic
    fun findRelativeFile(baseName: String?, sibling: PsiFile): PsiFile? {
        val parentDirectory = sibling.parent

        if (parentDirectory != null) {
            val candidate = parentDirectory.findFile("$baseName.g4")

            if (candidate is FileRoot) {
                return candidate
            }
        }

        return null
    }
}
