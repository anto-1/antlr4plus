package com.antlr4plus.timeline

import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.IntStream
import org.antlr.v4.runtime.LexerNoViableAltException
import org.antlr.v4.runtime.Token
import org.antlr.v4.tool.LexerGrammar

/** Records lexer matches, including skipped segments, with their mode and stack depth for the timeline. */
class AllTokenCapturingLexerInterpreter
private constructor(
    lexerGrammar: LexerGrammar,
    input: CharStream,
    channelNames: Collection<String>,
    modeNames: Collection<String>,
    private val ruleActions: Map<Int, String>,
) :
    org.antlr.v4.runtime.LexerInterpreter(
        lexerGrammar.name,
        lexerGrammar.vocabulary,
        lexerGrammar.ruleNames.toList(),
        channelNames,
        modeNames,
        lexerGrammar.atn,
        input,
    ) {
    val allTokensWithMode = mutableListOf<TokenWithMode>()

    /** Approximates selected mode-changing action patterns without executing target-language code. */
    override fun action(
        localctx: org.antlr.v4.runtime.RuleContext?,
        ruleIndex: Int,
        actionIndex: Int,
    ) {
        val actionText = ruleActions[ruleIndex] ?: return

        when {
            actionText.contains("handleEndArgument") || actionText.contains("handleEndAction") -> {
                if (_modeStack.size() > 0) popMode()
            }
            actionText.contains("handleBeginArgument") -> {
                val argMode = modeNames.indexOf("Argument")
                if (argMode >= 0) pushMode(argMode)
            }
            actionText.contains("handleOptionsLBrace") -> {}
            actionText.contains("popMode()") || actionText.contains("popMode (") -> {
                if (_modeStack.size() > 0) popMode()
            }
            actionText.contains("pushMode") -> {
                val match = Regex("""pushMode\s*\(\s*(\w+)\s*\)""").find(actionText)
                if (match != null) {
                    val modeName = match.groupValues[1]
                    val modeIndex = modeNames.indexOf(modeName)
                    if (modeIndex >= 0) pushMode(modeIndex)
                }
            }
        }
    }

    /** Based on ANTLR Lexer.nextToken; records each match before continuing on SKIP or MORE. */
    override fun nextToken(): Token {
        outer@ while (true) {
            _token = null
            _channel = Token.DEFAULT_CHANNEL
            _tokenStartCharIndex = _input.index()
            _tokenStartCharPositionInLine = interpreter.charPositionInLine
            _tokenStartLine = interpreter.line
            _text = null
            _type = Token.INVALID_TYPE

            if (_input.LA(1) == IntStream.EOF) {
                _hitEOF = true
                return emitEOF()
            }

            // Record the mode that matched this segment, before actions change it.
            val modeBeforeMatch = _mode
            val modeDepthBeforeMatch = _modeStack.size()
            val modeNameBeforeMatch =
                if (modeBeforeMatch >= 0 && modeBeforeMatch < modeNames.size) {
                    modeNames[modeBeforeMatch]
                } else {
                    "MODE_$modeBeforeMatch"
                }

            var ttype: Int
            try {
                ttype = interpreter.match(_input, _mode)
            } catch (e: LexerNoViableAltException) {
                notifyListeners(e)
                recover(e)
                ttype = SKIP
            } catch (e: java.util.EmptyStackException) {
                // Recover from an empty mode stack and advance input to keep lexing.
                if (_input.LA(1) != IntStream.EOF) {
                    _input.consume()
                }
                ttype = SKIP
            }

            if (_type == Token.INVALID_TYPE) _type = ttype

            if (_type != Token.EOF) {
                captureToken(ttype, modeBeforeMatch, modeDepthBeforeMatch, modeNameBeforeMatch)
            }

            if (_type == SKIP) {
                continue@outer
            }
            if (_type == MORE) {
                continue@outer
            }

            if (_token == null) emit()
            return _token
        }
    }

    /** Creates a token directly because skipped matches never reach emit(). */
    private fun captureToken(
        matchedType: Int,
        mode: Int,
        modeDepth: Int,
        modeName: String,
    ) {

        // SKIP and MORE replace _type, so retain the simulator's matched type.
        val actualType = if (_type == SKIP || _type == MORE) matchedType else _type

        val token =
            CommonToken(
                _tokenFactorySourcePair,
                actualType,
                _channel,
                _tokenStartCharIndex,
                _input.index() - 1,
            )
        token.line = _tokenStartLine
        token.charPositionInLine = _tokenStartCharPositionInLine
        if (_text != null) {
            token.text = _text
        }

        allTokensWithMode.add(
            TokenWithMode(
                token = token,
                mode = mode,
                modeName = modeName,
                modeDepth = modeDepth,
            ),
        )
    }

    fun buildTimeline(): TokenTimeline {
        val tokenTypeNames = mutableListOf<String>()
        for (i in 0..vocabulary.maxTokenType) {
            val name = vocabulary.getSymbolicName(i) ?: vocabulary.getLiteralName(i) ?: "<$i>"
            tokenTypeNames.add(name)
        }

        return TokenTimeline(
            tokens = allTokensWithMode.toList(),
            modeNames = modeNames.toList(),
            tokenTypeNames = tokenTypeNames,
        )
    }

    companion object {
        private data class GrammarMetadata(
            val channelNames: List<String>,
            val modeNames: List<String>,
            val ruleActions: Map<Int, String>,
        )

        // Metadata must not keep discarded grammars alive.
        private val grammarMetadataCache = java.util.WeakHashMap<LexerGrammar, GrammarMetadata>()

        /** Clear when grammars are regenerated so their cached metadata is recomputed. */
        fun clearCache() {
            grammarMetadataCache.clear()
        }

        fun create(grammar: LexerGrammar, input: CharStream): AllTokenCapturingLexerInterpreter {
            val metadata =
                grammarMetadataCache.getOrPut(grammar) {
                    val tempLexer = grammar.createLexerInterpreter(input)
                    val channelNames = tempLexer.channelNames?.toList() ?: listOf("DEFAULT_TOKEN_CHANNEL", "HIDDEN")
                    val modeNames = tempLexer.modeNames?.toList() ?: listOf("DEFAULT_MODE")

                    val ruleActions = extractRuleActions(grammar)

                    input.seek(0)

                    GrammarMetadata(channelNames, modeNames, ruleActions)
                }

            input.seek(0)

            return AllTokenCapturingLexerInterpreter(
                grammar,
                input,
                metadata.channelNames,
                metadata.modeNames,
                metadata.ruleActions,
            )
        }

        /** Concatenates each rule's action text for heuristic mode-change detection. */
        private fun extractRuleActions(grammar: LexerGrammar): Map<Int, String> {
            val actions = mutableMapOf<Int, String>()

            for (rule in grammar.rules.values) {
                val actionTexts = mutableListOf<String>()

                rule.namedActions?.values?.forEach { action ->
                    action.text?.let { actionTexts.add(it) }
                }

                rule.ast?.let { ast ->
                    findActionBlocks(ast, actionTexts)
                }

                if (actionTexts.isNotEmpty()) {
                    actions[rule.index] = actionTexts.joinToString(" ")
                }
            }

            return actions
        }

        private fun findActionBlocks(
            tree: org.antlr.runtime.tree.Tree,
            actions: MutableList<String>,
        ) {
            if (
                tree.type == org.antlr.v4.parse.ANTLRParser.ACTION ||
                    tree.type == org.antlr.v4.parse.ANTLRParser.SEMPRED
            ) {
                tree.text?.let { text ->
                    val cleaned = text.trim().removePrefix("{").removeSuffix("}").removeSuffix("?").trim()
                    if (cleaned.isNotEmpty()) {
                        actions.add(cleaned)
                    }
                }
            }

            for (i in 0 until tree.childCount) {
                findActionBlocks(tree.getChild(i), actions)
            }
        }
    }
}
