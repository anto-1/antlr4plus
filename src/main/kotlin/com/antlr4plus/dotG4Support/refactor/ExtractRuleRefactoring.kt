package com.antlr4plus.dotG4Support.refactor

import com.antlr4plus.dotG4Support.generators.LiteralLexerRules
import com.intellij.openapi.util.TextRange
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token

internal class ExtractRuleSelection
private constructor(
    val source: GrammarSource,
    val start: Int,
    val end: Int,
    private val rule: ParserRuleContext,
) {
    val isLexerRule: Boolean = rule is ANTLRv4Parser.LexerRuleContext
    val suggestedName: String = source.availableName(if (isLexerRule) "NEW_RULE" else "newRule")

    private val literalGeneration: LiteralLexerRules? by lazy {
        if (
            isLexerRule ||
                source.tokens.tokens.none {
                    it.type == ANTLRv4Lexer.STRING_LITERAL && source.start(it) >= start && source.end(it) <= end
                }
        )
            null
        else LiteralLexerRules.from(source.text)
    }

    // Changing the extracted parser rule's name does not change literal discovery order.
    val companionLexerRules: List<String> by lazy {
        literalGeneration?.rulesToPreserveOrder(extractionPlan(suggestedName).resultText()).orEmpty()
    }

    fun nameError(name: String): String? = source.nameError(name, isLexerRule)

    fun replacementRange(name: String, plan: GrammarEditPlan): TextRange {
        val shiftedStart =
            start +
                plan.edits
                    .filter { it.start < start && it.end <= start }
                    .sumOf { it.replacement.length - (it.end - it.start) }
        return TextRange(shiftedStart, shiftedStart + name.length)
    }

    fun plan(name: String): GrammarEditPlan? {
        if (nameError(name) != null) return null
        val extraction = extractionPlan(name)
        val companion =
            if (companionLexerRules.isEmpty()) null
            else literalGeneration!!.plan(companionLexerRules, source.text.length) ?: return null
        val plan = GrammarEditPlan(source.text, extraction.edits + companion?.edits.orEmpty())
        return plan.takeIf { GrammarSource.parse(it.resultText()) != null }
    }

    private fun extractionPlan(name: String): GrammarEditPlan {
        val insertion = source.insertionOffset(rule)
        val separator = if (insertion > 0 && source.text[insertion - 1] != '\n') "\n" else ""
        val modifier = if (isLexerRule) "fragment " else ""
        // A fragment is compiled under its own options, not the caller's caseInsensitive setting.
        val options =
            (rule as? ANTLRv4Parser.LexerRuleContext)?.optionsSpec()?.let { " ${source.textOf(it)}" }.orEmpty()
        val declaration = "$separator$modifier$name$options : ${source.text.substring(start, end)} ;\n"
        return GrammarEditPlan(
            source.text,
            listOf(
                GrammarTextEdit(start, end, name),
                GrammarTextEdit(insertion, insertion, declaration),
            ),
        )
    }

    companion object {
        private val scopedTokenTypes =
            setOf(
                ANTLRv4Lexer.BEGIN_ACTION,
                ANTLRv4Lexer.BEGIN_ARGUMENT,
                ANTLRv4Lexer.POUND,
                ANTLRv4Lexer.RARROW,
            )

        /** Accept complete adjacent elements or alternatives, never partial tokens or cross-rule selections. */
        fun find(text: String, selectionStart: Int, selectionEnd: Int): ExtractRuleSelection? {
            if (selectionStart < 0 || selectionEnd > text.length || selectionStart >= selectionEnd) return null
            val source = GrammarSource.parse(text) ?: return null
            val selected =
                source.tokens.tokens.filter {
                    it.channel == Token.DEFAULT_CHANNEL &&
                        it.type != Token.EOF &&
                        source.end(it) > selectionStart &&
                        source.start(it) < selectionEnd
                }
            val first = selected.firstOrNull() ?: return null
            val last = selected.last()
            if (source.start(first) < selectionStart || source.end(last) > selectionEnd) return null
            val container =
                source.contexts.firstOrNull { context ->
                    val units: List<ParserRuleContext> =
                        when (context) {
                            is ANTLRv4Parser.AlternativeContext -> context.element()
                            is ANTLRv4Parser.LexerElementsContext -> context.lexerElement()
                            is ANTLRv4Parser.AltListContext -> context.alternative()
                            is ANTLRv4Parser.RuleAltListContext -> context.labeledAlt()
                            is ANTLRv4Parser.LexerAltListContext -> context.lexerAlt()
                            else -> emptyList()
                        }
                    val from = units.indexOfFirst { it.start.tokenIndex == first.tokenIndex }
                    val to = units.indexOfLast { it.stop.tokenIndex == last.tokenIndex }
                    from >= 0 && to >= from
                } ?: return null
            val rule = source.containingRule(container) ?: return null

            // These constructs carry scope or rule-level behavior and cannot simply be moved
            // into a newly named rule. Lexer commands stay on their original token producer.
            if (selected.any { it.type in scopedTokenTypes }) return null
            if (
                source.contexts.any {
                    (it is ANTLRv4Parser.LabeledElementContext || it is ANTLRv4Parser.LabeledLexerElementContext) &&
                        it.start.tokenIndex >= first.tokenIndex &&
                        it.stop.tokenIndex <= last.tokenIndex
                }
            )
                return null

            val movedNames =
                selected
                    .filter { it.type == ANTLRv4Lexer.RULE_REF || it.type == ANTLRv4Lexer.TOKEN_REF }
                    .mapTo(mutableSetOf()) { it.text }
            // An implicit label such as $ID.text in the caller must not lose its token/rule.
            for (context in source.contexts) {
                if (
                    (context is ANTLRv4Parser.ActionBlockContext || context is ANTLRv4Parser.ArgActionBlockContext) &&
                        source.containingRule(context) === rule &&
                        grammarActionReferences(source.textOf(context)).any {
                            !it.nonLocal && it.name in movedNames
                        }
                )
                    return null
            }
            // Moving a leading recursive reference would turn supported direct left recursion
            // into unsupported indirect left recursion through the extracted rule.
            if (
                rule is ANTLRv4Parser.ParserRuleSpecContext &&
                    rule.ruleBlock().ruleAltList().labeledAlt().any {
                        it.alternative().start.tokenIndex in first.tokenIndex..last.tokenIndex &&
                            it.alternative().start.text == rule.RULE_REF().text
                    }
            )
                return null

            return ExtractRuleSelection(source, source.start(first), source.end(last), rule)
        }
    }
}
