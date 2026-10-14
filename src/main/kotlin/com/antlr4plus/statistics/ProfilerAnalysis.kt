package com.antlr4plus.statistics

import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import com.antlr4plus.util.rethrowFatalOrCancellation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.Tree

/** Visits nodes in preorder without retaining every sibling or using the call stack. */
private fun visitProfilerTree(root: Tree, visit: (Tree) -> Unit) {
    class Frame(val node: Tree, val childCount: Int, var nextChild: Int = 0)

    val pending = ArrayDeque<Frame>()
    fun enter(node: Tree) {
        ProgressManager.checkCanceled()
        visit(node)
        pending.addLast(Frame(node, node.childCount))
    }

    enter(root)
    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val frame = pending.last()
        if (frame.nextChild < frame.childCount) {
            frame.node.getChild(frame.nextChild++)?.let { enter(it) }
        } else {
            pending.removeLast()
        }
    }
}

/** Approximates decision executions with containing-rule counts, including unprofiled LL(1) paths. */
internal fun countDecisionInvocationsFromParseTree(
    parser: Parser,
    parseTree: ParseTree?,
): Map<Int, Long>? {
    if (parseTree == null) return null

    val atn = parser.atn
    val numDecisions = atn.decisionToState.size
    if (numDecisions == 0) return null

    val ruleCounts = mutableMapOf<Int, Long>()
    visitProfilerTree(parseTree) { node ->
        if (node is ParserRuleContext) {
            val ruleIndex = node.ruleIndex
            ruleCounts[ruleIndex] = (ruleCounts[ruleIndex] ?: 0L) + 1
        }
    }

    val decisionCounts = mutableMapOf<Int, Long>()
    for (decision in 0 until numDecisions) {
        ProgressManager.checkCanceled()
        val ruleIndex = atn.getDecisionState(decision).ruleIndex
        val ruleCount = ruleCounts[ruleIndex] ?: 0L
        // Every decision in a rule gets the same count, even if that decision was not reached.
        decisionCounts[decision] = ruleCount
    }

    return decisionCounts
}

internal fun collectMatchingRuleContexts(tree: ParseTree, ruleIndex: Int): List<ParserRuleContext> {
    val result = mutableListOf<ParserRuleContext>()

    visitProfilerTree(tree) { node ->
        if (node is ParserRuleContext && node.ruleIndex == ruleIndex) {
            result.add(node)
        }
    }
    return result
}

internal fun getKotlinRuleIndexByDecision(kotlinParser: Any?, decisionCount: Int): IntArray {
    if (kotlinParser == null) return IntArray(decisionCount) { -1 }

    return try {
        ProgressManager.checkCanceled()
        val getAtnMethod = kotlinParser.javaClass.getMethod("getAtn")
        val atn = getAtnMethod.invoke(kotlinParser) ?: return IntArray(decisionCount) { -1 }

        val getDecisionToStateMethod = atn.javaClass.getMethod("getDecisionToState")
        val decisionToState = getDecisionToStateMethod.invoke(atn) as? List<*> ?: return IntArray(decisionCount) { -1 }

        IntArray(decisionCount) { i ->
            ProgressManager.checkCanceled()
            val state = decisionToState.getOrNull(i) ?: return@IntArray -1
            try {
                val getRuleIndexMethod = state.javaClass.getMethod("getRuleIndex")
                getRuleIndexMethod.invoke(state) as? Int ?: -1
            } catch (e: Exception) {
                rethrowFatalOrCancellation(e)
                -1
            }
        }
    } catch (e: Exception) {
        rethrowFatalOrCancellation(e)
        IntArray(decisionCount) { -1 }
    }
}

/** Assigns each decision the parse-tree count of its containing rule as an approximation. */
internal fun countRuleExecutionsFromKotlinTree(
    tree: Tree?,
    ruleIndexByDecision: IntArray,
    decisionCount: Int,
    log: Logger,
): Map<Int, Long>? {
    log.info(
        "countRuleExecutionsFromKotlinTree: tree=${tree?.javaClass?.simpleName}, decisionCount=$decisionCount",
    )
    if (tree == null) {
        log.info("  tree is null, returning null")
        return null
    }

    val ruleCounts = mutableMapOf<Int, Long>()
    var nodeCount = 0

    visitProfilerTree(tree) { node ->
        nodeCount++
        if (node is KotlinTreeAdapter && !node.isTerminalNode()) {
            val ruleIndex = node.getRuleIndex()
            if (ruleIndex >= 0) {
                ruleCounts[ruleIndex] = (ruleCounts[ruleIndex] ?: 0L) + 1
            }
        }
    }

    log.info("  Traversed $nodeCount nodes, found ${ruleCounts.size} unique rules")
    log.info("  ruleCounts sample: ${ruleCounts.entries.take(5)}")

    val decisionCounts = mutableMapOf<Int, Long>()
    for (decision in 0 until decisionCount) {
        ProgressManager.checkCanceled()
        val ruleIndex = ruleIndexByDecision.getOrNull(decision) ?: continue
        if (ruleIndex >= 0) {
            decisionCounts[decision] = ruleCounts[ruleIndex] ?: 0L
        }
    }

    log.info(
        "  decisionCounts: ${decisionCounts.size} entries, total=${decisionCounts.values.sum()}",
    )
    return decisionCounts.ifEmpty { null }
}

data class UnifiedDecisionColorInfo(
    val hasAmbiguities: Boolean,
    val hasContextSensitivities: Boolean,
    val hasPredicateEvals: Boolean,
    val maxLookahead: Long,
)

internal fun getDecisionColorInfo(
    parsingResult: com.antlr4plus.parsing.ParsingResult?,
    decision: Int,
): UnifiedDecisionColorInfo? =
    when {
        parsingResult == null -> null
        parsingResult.isKotlin ->
            parsingResult.getKotlinDecisionInfoList()?.getOrNull(decision)?.let {
                UnifiedDecisionColorInfo(
                    hasAmbiguities = it.ambiguitiesCount > 0,
                    hasContextSensitivities = it.contextSensitivitiesCount > 0,
                    hasPredicateEvals = it.predicateEvalsCount > 0,
                    maxLookahead = maxOf(it.llMaxLook, it.sllMaxLook),
                )
            }
        else ->
            parsingResult.parser?.parseInfo?.decisionInfo?.getOrNull(decision)?.let {
                UnifiedDecisionColorInfo(
                    hasAmbiguities = it.ambiguities.isNotEmpty(),
                    hasContextSensitivities = it.contextSensitivities.isNotEmpty(),
                    hasPredicateEvals = it.predicateEvals.isNotEmpty(),
                    maxLookahead = maxOf(it.LL_MaxLook, it.SLL_MaxLook),
                )
            }
    }
