package com.antlr4plus.dotG4Support.generators

import com.antlr4plus.dotG4Support.refactor.GrammarEditPlan
import com.antlr4plus.dotG4Support.refactor.GrammarSource
import com.antlr4plus.dotG4Support.refactor.GrammarTextEdit
import com.antlr4plus.dotG4Support.refactor.RefactorUtils.getLexerRuleNameFromLiteral
import com.intellij.openapi.progress.ProgressManager
import java.util.TreeSet
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.v4.Tool
import org.antlr.v4.automata.LexerATNFactory
import org.antlr.v4.misc.CharSupport
import org.antlr.v4.tool.Grammar

/** Candidate definitions and the source snapshot from which they were derived. */
internal class LiteralLexerRules
private constructor(
    val source: GrammarSource,
    val rules: List<String>,
    private val literals: List<String>,
    private val characters: List<IntArray>,
) {
    private val ruleIndices = rules.withIndex().associate { it.value to it.index }

    /** Definitions which must accompany a selection to retain the original lexer priority. */
    fun additionalRules(selected: List<String>): List<String> {
        val chosen = selected.toHashSet()
        return requiredRules(selected).orEmpty().filterNot { it in chosen }
    }

    private fun requiredRules(selected: List<String>): List<String>? {
        val included = BooleanArray(rules.size)
        for (rule in selected) included[ruleIndices[rule] ?: return null] = true
        var count = included.count { it }
        if (count == 0) return emptyList()
        if (count == rules.size) return rules
        val remaining =
            rules.indices.filterNot { included[it] }.groupBy { characters[it].size }.mapValues { TreeSet(it.value) }
        // Remaining implicit tokens precede every explicit rule. A selected token therefore
        // needs every later overlapping token, including dependencies of newly added tokens.
        for (i in rules.indices) {
            ProgressManager.checkCanceled()
            if (count == rules.size) break
            if (!included[i]) continue
            val candidates = remaining[characters[i].size]?.tailSet(i, false)?.iterator() ?: continue
            while (candidates.hasNext()) {
                ProgressManager.checkCanceled()
                val j = candidates.next()
                if (overlaps(i, j)) {
                    included[j] = true
                    candidates.remove()
                    count++
                }
            }
        }
        return rules.filterIndexed { index, _ -> included[index] }
    }

    /** Pin the later member of each reversed overlap, then include its priority dependencies. */
    fun rulesToPreserveOrder(after: String): List<String> {
        if (literals.size < 2) return emptyList()
        val reordered = from(after)?.literals ?: return emptyList()
        if (reordered == literals) return emptyList()
        val positions = reordered.withIndex().associate { it.value to it.index }
        val newPositions = literals.map { positions[it] ?: return emptyList() }
        val selected = linkedSetOf<String>()
        for (i in literals.indices) {
            for (j in i + 1 until literals.size) {
                ProgressManager.checkCanceled()
                if (newPositions[i] > newPositions[j] && overlaps(i, j)) selected.add(rules[j])
            }
        }
        return requiredRules(selected.toList()).orEmpty()
    }

    private fun overlaps(first: Int, second: Int): Boolean {
        val left = characters[first]
        val right = characters[second]
        if (left.size != right.size) return false // Longest-match precedence is unaffected.
        for (i in left.indices step 2) {
            ProgressManager.checkCanceled()
            if (
                left[i] != right[i] && left[i] != right[i + 1] && left[i + 1] != right[i] && left[i + 1] != right[i + 1]
            )
                return false
        }
        return true
    }

    fun plan(selected: List<String>, caretOffset: Int): GrammarEditPlan? {
        if (caretOffset !in 0..source.text.length) return null
        val definitions = requiredRules(selected)?.takeIf { it.isNotEmpty() } ?: return null

        var insertion = source.text.length
        for (rule in source.rules) {
            ProgressManager.checkCanceled()
            val start = source.start(rule.start)
            if (caretOffset < start) {
                insertion = start
                break
            }
            if (caretOffset <= source.end(rule.stop)) {
                insertion = source.insertionOffset(rule)
                break
            }
        }
        // Explicit replacements for implicit literal tokens must precede existing lexer rules.
        source.rules.filterIsInstance<ANTLRv4Parser.LexerRuleContext>().firstOrNull()?.let {
            insertion = minOf(insertion, source.start(it.start))
        }
        val separator = if (insertion > 0 && source.text[insertion - 1] != '\n') "\n" else ""
        val declaration = separator + definitions.joinToString("\n") + "\n"
        val plan = GrammarEditPlan(source.text, listOf(GrammarTextEdit(insertion, insertion, declaration)))
        return plan.takeIf { GrammarSource.parse(it.resultText()) != null }
    }

    companion object {
        fun from(text: String): LiteralLexerRules? {
            val source = GrammarSource.parse(text) ?: return null
            val kind = source.tree.grammarType()
            if (kind.LEXER() != null || kind.PARSER() != null) return null

            val literals = linkedSetOf<String>()
            for (context in source.contexts) {
                ProgressManager.checkCanceled()
                val literal =
                    when (context) {
                        is ANTLRv4Parser.TerminalContext -> context.STRING_LITERAL()
                        is ANTLRv4Parser.SetElementContext -> context.STRING_LITERAL()
                        else -> null
                    } ?: continue
                if (source.containingRule(context) is ANTLRv4Parser.ParserRuleSpecContext && literal.text != "''") {
                    literals.add(literal.text)
                }
            }

            // Use ANTLR's alias rules, including commands/actions, so a second definition cannot
            // make an existing literal-to-token mapping ambiguous. Fragments are not aliases.
            val tool = Tool().apply { removeListeners() }
            val grammar = tool.parseGrammarFromString(text) ?: return null
            if (grammar.hasErrors) return null
            for (alias in Grammar.getStringLiteralAliasesFromLexerRules(grammar).orEmpty()) {
                ProgressManager.checkCanceled()
                literals.remove(alias.b.text)
            }

            val occupied = source.occupiedNames.toMutableSet()
            occupied.addAll(LexerATNFactory.getCommonConstants())
            val nextSuffix = mutableMapOf<String, Int>()
            val definitions = literals.map { literal ->
                ProgressManager.checkCanceled()
                val base = getLexerRuleNameFromLiteral(literal)
                val name =
                    if (occupied.add(base)) base
                    else {
                        var suffix = nextSuffix[base] ?: 1
                        var available: String
                        do {
                            ProgressManager.checkCanceled()
                            available = base + suffix++
                        } while (!occupied.add(available))
                        nextSuffix[base] = suffix
                        available
                    }
                "$name : $literal ;"
            }
            val caseOption = grammar.getOptionAST(Grammar.caseInsensitiveOptionName)?.text
            if (caseOption != null && caseOption != "true" && caseOption != "false") return null
            val caseInsensitive = caseOption == "true"
            val characters = literals.map { literal ->
                val decoded =
                    try {
                        CharSupport.getStringFromGrammarStringLiteral(literal)
                    } catch (_: IllegalArgumentException) {
                        null
                    } ?: return null
                val codePoints = decoded.codePoints().toArray()
                val choices = IntArray(codePoints.size * 2)
                for (i in codePoints.indices) {
                    ProgressManager.checkCanceled()
                    val point = codePoints[i]
                    // Match LexerATNFactory's two alternatives for a case-insensitive character.
                    val lower = if (caseInsensitive) Character.toLowerCase(point) else point
                    val upper = if (caseInsensitive) Character.toUpperCase(point) else point
                    choices[i * 2] = if (lower == upper) point else lower
                    choices[i * 2 + 1] = if (lower == upper) point else upper
                }
                choices
            }
            return LiteralLexerRules(source, definitions, literals.toList(), characters)
        }
    }
}
