package com.antlr4plus.dotG4Support.refactor

import com.antlr4plus.parsing.ParsingUtils
import com.intellij.openapi.editor.Document
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.v4.automata.LexerATNFactory
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree

/** A parsed document snapshot. Token positions count code points; all exposed offsets count UTF-16 units. */
internal class GrammarSource
private constructor(
    val text: String,
    val parser: ANTLRv4Parser,
    val tree: ANTLRv4Parser.GrammarSpecContext,
) {
    val tokens = parser.tokenStream as CommonTokenStream
    private val offsets = codePointOffsets(text)

    val contexts: List<ParserRuleContext> by lazy {
        val result = mutableListOf<ParserRuleContext>()
        val pending = ArrayDeque<ParseTree>()
        pending.addLast(tree)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node is ParserRuleContext) result.add(node)
            for (i in node.childCount - 1 downTo 0) pending.addLast(node.getChild(i))
        }
        result
    }

    val rules: List<ParserRuleContext> by lazy {
        contexts.filter { it is ANTLRv4Parser.ParserRuleSpecContext || it is ANTLRv4Parser.LexerRuleContext }
    }

    // Include referenced names so new rules cannot silently shadow imported rules or tokens.
    val occupiedNames: Set<String> by lazy {
        tokens.tokens
            .filter { it.type == ANTLRv4Lexer.RULE_REF || it.type == ANTLRv4Lexer.TOKEN_REF }
            .mapTo(mutableSetOf("EOF")) { it.text }
    }

    fun start(token: Token): Int = offsets[token.startIndex]

    fun end(token: Token): Int = offsets[token.stopIndex + 1]

    fun textOf(context: ParserRuleContext): String = text.substring(start(context.start), end(context.stop))

    fun definition(name: String): ParserRuleContext? = rules.firstOrNull { nameToken(it).text == name }

    fun nameToken(rule: ParserRuleContext): Token =
        when (rule) {
            is ANTLRv4Parser.ParserRuleSpecContext -> rule.RULE_REF().symbol
            is ANTLRv4Parser.LexerRuleContext -> rule.TOKEN_REF().symbol
            else -> error("Not a grammar rule")
        }

    fun containingRule(node: ParseTree): ParserRuleContext? {
        var current: ParseTree? = node
        while (current != null) {
            if (current is ANTLRv4Parser.ParserRuleSpecContext || current is ANTLRv4Parser.LexerRuleContext) {
                return current as ParserRuleContext
            }
            current = current.parent
        }
        return null
    }

    /** Insert before the next declaration, leaving trailing comments with the original rule. */
    fun insertionOffset(rule: ParserRuleContext): Int {
        val next = ParsingUtils.nextRealToken(tokens, rule.stop.tokenIndex) ?: return text.length
        return if (next.type == Token.EOF) text.length else start(next)
    }

    fun nameError(name: String, lexerRule: Boolean): String? {
        val nameTokens = ParsingUtils.tokenizeANTLRGrammar(name).tokens
        val expectedType = if (lexerRule) ANTLRv4Lexer.TOKEN_REF else ANTLRv4Lexer.RULE_REF
        if (nameTokens.size != 2 || nameTokens[0].type != expectedType || nameTokens[0].text != name) {
            return if (lexerRule) "Use a lexer rule name beginning with an uppercase letter."
            else "Use a parser rule name beginning with a lowercase letter."
        }
        if (lexerRule) {
            if (name in LexerATNFactory.getCommonConstants()) return "The name '$name' is reserved by ANTLR."
            if (name.startsWith("T__")) return "Names beginning with 'T__' are reserved for implicit literal tokens."
        }
        return if (name in occupiedNames) "The name '$name' is already declared or referenced in this grammar."
        else null
    }

    fun availableName(base: String): String {
        if (base !in occupiedNames) return base
        var suffix = 1
        while ("$base$suffix" in occupiedNames) suffix++
        return "$base$suffix"
    }

    companion object {
        fun parse(text: String): GrammarSource? {
            val parsed = ParsingUtils.parseANTLRGrammar(text)
            if (!parsed.syntaxErrorListener?.syntaxErrors.isNullOrEmpty()) return null
            if ((parsed.parser!!.tokenStream as CommonTokenStream).tokens.any { it.type == ANTLRv4Lexer.ERRCHAR })
                return null
            return GrammarSource(text, parsed.parser as ANTLRv4Parser, parsed.tree as ANTLRv4Parser.GrammarSpecContext)
        }
    }
}

internal fun codePointOffsets(text: String): IntArray =
    IntArray(text.codePointCount(0, text.length) + 1).also { offsets ->
        for (i in 1 until offsets.size) {
            offsets[i] = offsets[i - 1] + Character.charCount(text.codePointAt(offsets[i - 1]))
        }
    }

internal data class GrammarTextEdit(val start: Int, val end: Int, val replacement: String)

/** Validates every range before touching the document and refuses to apply to a newer snapshot. */
internal class GrammarEditPlan(val source: String, edits: List<GrammarTextEdit>) {
    val edits = edits.sortedByDescending { it.start }

    init {
        var boundary = source.length
        for (edit in this.edits) {
            require(edit.start >= 0 && edit.start <= edit.end && edit.end <= boundary) { "Invalid or overlapping edit" }
            boundary = edit.start
        }
    }

    fun resultText(): String =
        StringBuilder(source)
            .apply {
                edits.forEach { replace(it.start, it.end, it.replacement) }
            }
            .toString()

    fun applyTo(document: Document): Boolean {
        if (
            document.text != source ||
                !document.isWritable ||
                edits.any { document.getRangeGuard(it.start, it.end) != null }
        )
            return false
        edits.forEach { document.replaceString(it.start, it.end, it.replacement) }
        return true
    }
}
