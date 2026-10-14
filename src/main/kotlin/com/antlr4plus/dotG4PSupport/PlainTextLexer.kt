package com.antlr4plus.dotG4PSupport

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

class PlainTextLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd: Int = 0
    private var currentPosition: Int = 0
    private val plainTextTokenType = PlainTextTokenType("PLAIN_TEXT")

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufferEnd = endOffset
        this.currentPosition = startOffset
    }

    override fun getTokenType(): IElementType? = if (currentPosition < bufferEnd) plainTextTokenType else null

    override fun getTokenStart(): Int = currentPosition

    override fun getTokenEnd(): Int = bufferEnd

    override fun advance() {
        currentPosition = bufferEnd
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = buffer.length

    override fun getState(): Int = 0
}
