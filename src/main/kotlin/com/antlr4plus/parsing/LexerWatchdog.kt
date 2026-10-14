package com.antlr4plus.parsing

import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenStream

/** Aborts interpretation after repeated checks at the same token-stream index. */
class LexerWatchdog(
    private val tokenStream: TokenStream,
    private val previewParser: PreviewParser,
) {
    private var currentIndex = -1
    private var iterationsOnCurrentIndex = 0

    fun checkLexerIsNotStuck() {
        if (currentIndex == tokenStream.index()) {
            iterationsOnCurrentIndex++
        } else {
            currentIndex = tokenStream.index()
            iterationsOnCurrentIndex = 1
        }

        if (iterationsOnCurrentIndex > THRESHOLD) {
            val token = tokenStream.get(currentIndex)
            val displayName =
                if (token.type == Token.EOF) token.text else previewParser.vocabulary.getDisplayName(token.type)

            throw object :
                RecognitionException(
                    "interpreter was killed after $THRESHOLD iterations on token '$displayName'",
                    previewParser,
                    tokenStream,
                    previewParser.context,
                ) {
                override fun getOffendingToken(): Token = token
            }
        }
    }

    companion object {
        private const val THRESHOLD = 50
    }
}
