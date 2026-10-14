package com.antlr4plus.preview

import com.antlr4plus.preview.ShowAmbigTreesDialog.Companion.AmbiguityTreesResult
import com.antlr4plus.util.rethrowFatalOrCancellation
import com.intellij.openapi.progress.ProgressManager
import java.util.BitSet
import org.antlr.v4.runtime.BailErrorStrategy
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenStream
import org.antlr.v4.runtime.Vocabulary
import org.antlr.v4.runtime.atn.LookaheadEventInfo
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.GrammarParserInterpreter
import org.antlr.v4.tool.LexerGrammar

/** Maps compiled token types to grammar token types by symbolic name. */
private fun createTokenTypeMapping(
    compiledVocab: Vocabulary,
    lexerGrammar: LexerGrammar,
): Map<Int, Int> {
    val mapping = mutableMapOf<Int, Int>()

    for (compiledType in 0..compiledVocab.maxTokenType) {
        ProgressManager.checkCanceled()
        val symbolicName = compiledVocab.getSymbolicName(compiledType)
        if (symbolicName != null) {
            val grammarType = lexerGrammar.getTokenType(symbolicName)
            if (grammarType != Token.INVALID_TYPE) {
                mapping[compiledType] = grammarType
            }
        }
    }

    mapping[Token.EOF] = Token.EOF

    return mapping
}

private fun createRemappedToken(original: Token, newType: Int): CommonToken =
    CommonToken(original).apply { type = newType }

/** Copies tokens and remaps known types for the grammar ATN, preserving source positions. */
internal fun createRemappedTokenStream(
    originalTokens: TokenStream,
    compiledVocab: Vocabulary,
    lexerGrammar: LexerGrammar,
): CommonTokenStream {
    val typeMapping = createTokenTypeMapping(compiledVocab, lexerGrammar)

    val remappedTokens = mutableListOf<Token>()
    for (i in 0 until originalTokens.size()) {
        ProgressManager.checkCanceled()
        val original = originalTokens.get(i)
        val originalType = original.type
        val newType = typeMapping[originalType] ?: originalType
        remappedTokens.add(createRemappedToken(original, newType))
    }

    val tokenSource = ListTokenSource(remappedTokens)
    val remappedStream = CommonTokenStream(tokenSource)
    remappedStream.fill()

    return remappedStream
}

/** Returns the first preorder match for the rule that covers the inclusive token range. */
private fun findRuleContext(
    tree: ParserRuleContext,
    ruleIndex: Int,
    startToken: Int,
    stopToken: Int,
): ParserRuleContext? =
    findContext(tree) { node ->
        node.ruleIndex == ruleIndex && node.sourceInterval.let { it.a <= startToken && it.b >= stopToken }
    }

/** Searches lazily with one frame per ancestor, preserving preorder or child-first selection. */
private fun findContext(
    root: ParserRuleContext,
    childrenFirst: Boolean = false,
    matches: (ParserRuleContext) -> Boolean,
): ParserRuleContext? {
    class Frame(val node: ParserRuleContext, val childCount: Int = node.childCount, var nextChild: Int = 0)

    ProgressManager.checkCanceled()
    if (!childrenFirst && matches(root)) return root
    val pending = ArrayDeque<Frame>()
    pending.addLast(Frame(root))
    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val frame = pending.last()
        if (frame.nextChild < frame.childCount) {
            val child = frame.node.getChild(frame.nextChild++) as? ParserRuleContext ?: continue
            if (!childrenFirst && matches(child)) return child
            pending.addLast(Frame(child))
        } else {
            pending.removeLast()
            if (childrenFirst && matches(frame.node)) return frame.node
        }
    }
    return null
}

/** Matches ANTLR's leftmost, lowest enclosing context, including a missing stop after parser bailout. */
private fun findEnclosingContext(tree: ParserRuleContext, startToken: Int, stopToken: Int): ParserRuleContext? =
    findContext(tree, childrenFirst = true) { node ->
        val start = node.getStart()
        val stop = node.getStop()
        start != null && startToken >= start.tokenIndex && (stop == null || stopToken <= stop.tokenIndex)
    }

/** Replays each ambiguous alternative with GrammarParserInterpreter to retain outer-alt metadata. */
internal fun getAmbiguousParseTrees(
    grammar: Grammar,
    tokens: TokenStream,
    decision: Int,
    ambigAlts: BitSet,
    startIndex: Int,
    stopIndex: Int,
    startRuleIndex: Int,
): AmbiguityTreesResult {
    val trees = mutableListOf<ParserRuleContext>()
    val decisionRoots = mutableListOf<ParserRuleContext?>()
    val alternativeNumbers = mutableListOf<Int>()

    val decisionState = grammar.atn.getDecisionState(decision)
    val ambiguousRuleIndex = decisionState?.ruleIndex ?: -1

    val adjustedStopIndex = stopIndexBeforeEof(tokens, stopIndex)

    var alt = ambigAlts.nextSetBit(0)
    while (alt >= 0) {
        ProgressManager.checkCanceled()
        val parser = createAlternativeParser(grammar, tokens, decision, startIndex, alt)

        val result = parser.parse(startRuleIndex)

        val ambigRuleCtx =
            if (ambiguousRuleIndex >= 0) {
                findRuleContext(result, ambiguousRuleIndex, startIndex, adjustedStopIndex)
            } else null

        val decisionRoot = parser.overrideDecisionRoot

        val subtreeToAdd =
            ambigRuleCtx ?: decisionRoot ?: findEnclosingContext(result, startIndex, adjustedStopIndex) ?: result

        trees.add(subtreeToAdd)
        decisionRoots.add(findNodeInTree(subtreeToAdd, decisionRoot))
        alternativeNumbers.add(alt)
        alt = ambigAlts.nextSetBit(alt + 1)
    }

    return AmbiguityTreesResult(trees, decisionRoots, alternativeNumbers)
}

/** Replays alternatives from lookahead configurations, or all decision alternatives when absent. */
internal fun getLookaheadParseTrees(
    grammar: Grammar,
    tokens: TokenStream,
    decision: Int,
    lookaheadInfo: LookaheadEventInfo,
    startIndex: Int,
    stopIndex: Int,
    startRuleIndex: Int,
): AmbiguityTreesResult {
    val trees = mutableListOf<ParserRuleContext>()
    val decisionRoots = mutableListOf<ParserRuleContext?>()
    val alternativeNumbers = mutableListOf<Int>()

    val decisionState = grammar.atn.getDecisionState(decision)
    val lookaheadRuleIndex = decisionState?.ruleIndex ?: -1

    val altsToTry = BitSet()
    val configAlts = lookaheadInfo.configs?.alts
    if (configAlts != null && !configAlts.isEmpty) {
        altsToTry.or(configAlts)
    } else {
        val numAlts = decisionState?.numberOfTransitions ?: 0
        for (alt in 1..numAlts) {
            altsToTry.set(alt)
        }
    }

    val adjustedStopIndex = stopIndexBeforeEof(tokens, stopIndex)

    var alt = altsToTry.nextSetBit(0)
    while (alt >= 0) {
        ProgressManager.checkCanceled()
        try {
            val parser = createAlternativeParser(grammar, tokens, decision, startIndex, alt)

            val result = parser.parse(startRuleIndex)

            val lookaheadRuleCtx =
                if (lookaheadRuleIndex >= 0) {
                    findRuleContext(result, lookaheadRuleIndex, startIndex, adjustedStopIndex)
                } else null

            val decisionRoot = parser.overrideDecisionRoot

            val subtreeToAdd =
                lookaheadRuleCtx
                    ?: decisionRoot
                    ?: findEnclosingContext(result, startIndex, adjustedStopIndex)
                    ?: result

            val replayedDecisionRoot = findNodeInTree(subtreeToAdd, decisionRoot)
            trees.add(subtreeToAdd)
            decisionRoots.add(replayedDecisionRoot)
            alternativeNumbers.add(alt)
        } catch (e: ParseCancellationException) {
            // An alternative may legitimately fail under BailErrorStrategy.
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
        }
        alt = altsToTry.nextSetBit(alt + 1)
    }

    return AmbiguityTreesResult(trees, decisionRoots, alternativeNumbers)
}

private fun createAlternativeParser(
    grammar: Grammar,
    tokens: TokenStream,
    decision: Int,
    startIndex: Int,
    alt: Int,
): GrammarParserInterpreter =
    grammar.createGrammarParserInterpreter(tokens).apply {
        errorHandler = BailErrorStrategy()
        removeErrorListeners()
        removeParseListeners()
        interpreter.predictionMode = PredictionMode.LL_EXACT_AMBIG_DETECTION

        reset()
        addDecisionOverride(decision, startIndex, alt)
    }

/** Excludes the trailing EOF token from the displayed region. */
private fun stopIndexBeforeEof(tokens: TokenStream, stopIndex: Int): Int =
    if (stopIndex >= tokens.size() - 1) {
        tokens.size() - 2
    } else {
        stopIndex
    }

private fun findNodeInTree(
    tree: ParserRuleContext,
    target: ParserRuleContext?,
): ParserRuleContext? {
    if (target == null) return null
    return findContext(tree) { it === target }
}
