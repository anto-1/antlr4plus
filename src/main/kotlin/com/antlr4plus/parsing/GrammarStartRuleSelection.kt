package com.antlr4plus.parsing

import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.Rule
import org.antlr.v4.tool.ast.GrammarAST

/** Prefers rules referencing EOF, then common entry-point names, then the first rule. */
internal fun findBestStartRule(g: Grammar): String {
    val rules = g.rules
    val ruleList = (0 until rules.size).map { rules.getElement(it) }

    for (rule in ruleList) {
        if (ruleReferencesEOF(rule)) {
            return rule.name
        }
    }

    val commonEntryPointNames =
        setOf(
            "compilationunit",
            "compilation_unit",
            "program",
            "file",
            "start",
            "root",
            "module",
            "source",
            "document",
            "input",
            "translationunit",
            "translation_unit",
        )
    for (rule in ruleList) {
        if (rule.name.lowercase() in commonEntryPointNames) {
            return rule.name
        }
    }

    return ruleList.firstOrNull()?.name ?: ""
}

private fun ruleReferencesEOF(rule: Rule): Boolean {
    val alt = rule.alt ?: return false
    for (i in 1 until alt.size) { // ANTLR alternatives are indexed from one.
        val alternative = alt[i] ?: continue
        val ast = alternative.ast ?: continue
        if (astContainsEOF(ast)) {
            return true
        }
    }
    return false
}

private fun astContainsEOF(node: GrammarAST): Boolean {
    if (node.text == "EOF") {
        return true
    }
    for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        if (child is GrammarAST && astContainsEOF(child)) {
            return true
        }
    }
    return false
}
