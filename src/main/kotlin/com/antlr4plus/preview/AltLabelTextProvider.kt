package com.antlr4plus.preview

import java.util.*
import org.antlr.v4.gui.TreeTextProvider
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode
import org.antlr.v4.runtime.tree.Tree
import org.antlr.v4.runtime.tree.Trees
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.GrammarInterpreterRuleContext
import org.antlr.v4.tool.Rule

class AltLabelTextProvider(private val parser: Parser, private val g: Grammar) : TreeTextProvider {

    fun getAltLabels(r: Rule): Array<String?>? {
        val altLabelsMap = r.getAltLabels() ?: return null
        val altLabels = arrayOfNulls<String>(r.originalNumberOfAlts + 1)
        altLabelsMap.forEach { (altLabel, pairs) ->
            pairs?.forEach { pair ->
                pair.a?.let { index -> altLabels[index] = altLabel }
            }
        }
        return altLabels
    }

    override fun getText(node: Tree): String? {
        if (node is GrammarInterpreterRuleContext) {
            val r = g.getRule(node.getRuleIndex())
            val altLabels = getAltLabels(r)
            val name = r.name
            val outerAltNum = node.getOuterAltNum()
            return if (altLabels != null) {
                if (outerAltNum >= 0 && outerAltNum < altLabels.size) {
                    name + ":" + altLabels[outerAltNum]
                } else {
                    name
                }
            } else if (r.originalNumberOfAlts > 1) {
                "$name:$outerAltNum"
            } else {
                name
            }
        } else if (node is TerminalNode) {
            return getLabelForToken(node.symbol)
        }
        return Trees.getNodeText(node, listOf<String?>(*parser.ruleNames))
    }

    private fun getLabelForToken(token: Token): String {
        val text = token.text
        if (text == "<EOF>") {
            return text
        }

        // Use the grammar vocabulary because token types may have been remapped.
        val tokenName = g.getTokenName(token.type)
        if (tokenName == null || tokenName.startsWith("'")) {
            return text
        }
        if (text.uppercase(Locale.ROOT) == tokenName) { // Collapse labels such as IMPORT:import.
            return tokenName
        }
        return "$tokenName:$text"
    }
}
