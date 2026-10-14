package com.antlr4plus.preview.tree

import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import com.antlr4plus.util.rethrowFatalOrCancellation
import java.util.*
import org.antlr.v4.gui.TreeTextProvider
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.Vocabulary
import org.antlr.v4.runtime.tree.TerminalNode
import org.antlr.v4.runtime.tree.Tree
import org.antlr.v4.runtime.tree.Trees

class DefaultTreeTextProvider(
    private val ruleNames: List<String?>?,
    private val vocabulary: Vocabulary? = null,
    private val kotlinVocabulary: Any? = null,
) : TreeTextProvider {
    override fun getText(node: Tree): String {
        // Trees.getNodeText cannot inspect the wrapped Kotlin runtime nodes.
        if (node is KotlinTreeAdapter) {
            return getTextForKotlinNode(node)
        }
        if (node is TerminalNode && vocabulary != null) {
            return getLabelForToken(node.symbol)
        }
        return Trees.getNodeText(node, ruleNames).toString()
    }

    private fun getTextForKotlinNode(node: KotlinTreeAdapter): String {
        if (node.isTerminalNode()) {
            return getKotlinTerminalLabel(node)
        }
        val ruleIndex = node.getRuleIndex()
        if (ruleIndex >= 0 && ruleNames != null && ruleIndex < ruleNames.size) {
            return ruleNames[ruleIndex] ?: "rule$ruleIndex"
        }
        return node.text
    }

    private fun getKotlinTerminalLabel(node: KotlinTreeAdapter): String {
        val text = node.getTokenText()
        if (text == "<EOF>") return text

        val tokenType = node.getTokenType()
        if (tokenType < 0) return text

        val symbolicName = getKotlinSymbolicName(tokenType) ?: return text
        if (text.uppercase(Locale.ROOT) == symbolicName) {
            return symbolicName
        }
        return "$symbolicName:$text"
    }

    private fun getKotlinSymbolicName(tokenType: Int): String? = kotlinVocabulary?.let { vocab ->
        try {
            val method = vocab.javaClass.getMethod("getSymbolicName", Int::class.java)
            method.invoke(vocab, tokenType) as? String
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }
    }

    private fun getLabelForToken(token: Token): String {
        val text = token.text
        if (text == "<EOF>") {
            return text
        }

        val symbolicName = vocabulary?.getSymbolicName(token.type) ?: return text
        if (text.uppercase(Locale.ROOT) == symbolicName) {
            return symbolicName
        }
        return "$symbolicName:$text"
    }
}
