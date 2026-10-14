package com.antlr4plus.dotG4Support.refactor

import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.runtime.ANTLRStringStream
import org.antlr.runtime.CommonToken
import org.antlr.runtime.Token
import org.antlr.v4.parse.ActionSplitter
import org.antlr.v4.parse.ActionSplitterListener
import org.antlr.v4.runtime.CharStreams

internal data class GrammarActionReference(val name: String, val start: Int, val end: Int, val nonLocal: Boolean)

/** Use ANTLR's own action syntax, including escaped dollars, comments, and assignment expressions. */
internal fun grammarActionReferences(text: String): List<GrammarActionReference> {
    val references = mutableListOf<GrammarActionReference>()
    val pending = ArrayDeque<Pair<Int, String>>()
    pending.addLast(0 to maskActionComments(text))
    while (pending.isNotEmpty()) {
        val (offset, part) = pending.removeLast()
        fun record(token: Token, nonLocal: Boolean = false) {
            val span = token as CommonToken
            references.add(
                GrammarActionReference(token.text, offset + span.startIndex, offset + span.stopIndex + 1, nonLocal),
            )
        }
        fun expression(token: Token) {
            pending.addLast(offset + (token as CommonToken).startIndex to token.text)
        }
        val listener =
            object : ActionSplitterListener {
                override fun qualifiedAttr(expr: String, x: Token, y: Token) = record(x)

                override fun attr(expr: String, x: Token) = record(x)

                override fun setAttr(expr: String, x: Token, rhs: Token) {
                    record(x)
                    expression(rhs)
                }

                override fun nonLocalAttr(expr: String, x: Token, y: Token) = record(x, true)

                override fun setNonLocalAttr(expr: String, x: Token, y: Token, rhs: Token) {
                    record(x, true)
                    expression(rhs)
                }

                override fun text(text: String) {}
            }
        val splitter = ActionSplitter(ANTLRStringStream(part), listener)
        while (splitter.nextToken().type != Token.EOF) {
            /* callbacks collect reference spans */
        }
    }
    return references
}

private fun maskActionComments(text: String): String {
    // ActionSplitter can consume a comment prefix as ordinary TEXT before encountering '$'.
    // Mask comment tokens first, preserving strings (where ANTLR still expands attributes)
    // and UTF-16 offsets. The outer braces put the grammar lexer in its action mode.
    val wrapped = "{$text}"
    val offsets = codePointOffsets(wrapped)
    val lexer = ANTLRv4Lexer(CharStreams.fromString(wrapped))
    lexer.removeErrorListeners()
    val masked = StringBuilder(text)
    for (token in lexer.allTokens) {
        if (token.type != ANTLRv4Lexer.ACTION_CONTENT || !(token.text.startsWith("/*") || token.text.startsWith("//")))
            continue
        val from = (offsets[token.startIndex] - 1).coerceAtLeast(0)
        val to = (offsets[token.stopIndex + 1] - 1).coerceAtMost(text.length)
        for (index in from until to) {
            if (masked[index] != '\r' && masked[index] != '\n') masked.setCharAt(index, ' ')
        }
    }
    return masked.toString()
}
