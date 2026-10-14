package com.antlr4plus.parsing

import org.antlr.v4.runtime.CharStream
import org.antlr.v4.tool.LexerGrammar

enum class CaseChangingStrategy {
    LEAVE_AS_IS {
        override fun applyTo(source: CharStream): CharStream = source

        override fun toString(): String = "Leave as-is"
    },
    FORCE_UPPERCASE {
        override fun applyTo(source: CharStream): CharStream = CaseChangingCharStream(stream = source, upper = true)

        override fun toString(): String = "UpperCase"
    },
    FORCE_LOWERCASE {
        override fun applyTo(source: CharStream): CharStream = CaseChangingCharStream(source, false)

        override fun toString(): String = "LowerCase"
    };

    abstract fun applyTo(source: CharStream): CharStream?

    internal fun foldCodePoint(value: Int): Int =
        when {
            value <= 0 -> value
            this == FORCE_UPPERCASE -> Character.toUpperCase(value)
            this == FORCE_LOWERCASE -> Character.toLowerCase(value)
            else -> value
        }

    /** Applies Unicode simple case mapping, preserving one code point per input code point. */
    fun applyToText(source: String): String {
        if (this == LEAVE_AS_IS) return source
        return buildString(source.length) {
            var offset = 0
            while (offset < source.length) {
                val codePoint = source.codePointAt(offset)
                appendCodePoint(foldCodePoint(codePoint))
                offset += Character.charCount(codePoint)
            }
        }
    }

    companion object {
        fun isCaseInsensitive(lexerGrammar: LexerGrammar?): Boolean =
            lexerGrammar?.getOptionString("caseInsensitive")?.equals("true", ignoreCase = true) == true

        /** Uses the explicit strategy, or lowercase folding when caseInsensitive=true. */
        fun getEffectiveStrategy(
            lexerGrammar: LexerGrammar?,
            userStrategy: CaseChangingStrategy,
        ): CaseChangingStrategy =
            when {
                userStrategy != LEAVE_AS_IS -> userStrategy
                isCaseInsensitive(lexerGrammar) -> FORCE_LOWERCASE
                else -> LEAVE_AS_IS
            }
    }
}
