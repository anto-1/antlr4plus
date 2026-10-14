package com.antlr4plus.statistics

import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.atn.ParseInfo

class ExpertProfilerTableDataModel(
    var parseInfo: ParseInfo,
    parser: Parser,
    private val manualInvocations: Map<Int, Long>? = null,
    /** Target total in milliseconds for scaling positive prediction-time totals. */
    expectedTotalPredictionTimeMs: Double? = null,
    /** Approximate executions per decision, based on occurrences of its containing rule in the tree. */
    private val ruleExecutions: Map<Int, Long>? = null,
) : ProfilerTableDataModel() {
    // Copy names so this model does not retain the parser.
    private val ruleNamesByDecision: Array<String?> =
        Array(parser.atn.decisionToState.size) { index ->
            parser.ruleNames[parser.atn.getDecisionState(index).ruleIndex]
        }

    private val timeNormalizationFactor: Double =
        parseInfo.predictionTimeNormalizationFactor(expectedTotalPredictionTimeMs)

    override val columnNames: Array<String>
        get() = Companion.columnNames

    override val columnToolTips: Array<String>
        get() = Companion.columnToolTips

    override fun getRowCount(): Int = parseInfo.decisionInfo.size

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val decisionInfo = parseInfo.decisionInfo[rowIndex]
        return when (columnIndex) {
            0 -> String.format("%s (%d)", ruleNamesByDecision[rowIndex], rowIndex)
            1 -> manualInvocations?.get(rowIndex) ?: decisionInfo.invocations
            2 -> decisionInfo.timeInPrediction.normalizedPredictionTimeMs(timeNormalizationFactor)
            3 -> parseInfo.getDFASize(rowIndex)
            4 -> decisionInfo.LL_Fallback
            5 -> decisionInfo.LL_TotalLook + decisionInfo.SLL_TotalLook
            6 -> decisionInfo.SLL_MinLook
            7 -> decisionInfo.LL_MinLook
            8 -> decisionInfo.SLL_MaxLook
            9 -> decisionInfo.LL_MaxLook
            10 -> decisionInfo.SLL_DFATransitions
            11 -> decisionInfo.SLL_ATNTransitions
            12 -> decisionInfo.LL_ATNTransitions
            13 -> decisionInfo.contextSensitivities.size
            14 -> decisionInfo.ambiguities.size
            15 -> decisionInfo.predicateEvals.size
            16 -> ruleExecutions?.get(rowIndex) ?: 0L
            else -> "n/a"
        }
    }

    companion object {
        val columnNames: Array<String> =
            arrayOf(
                "Rule",
                "#",
                "ms",
                "# DFA states",
                "LL failover",
                "Total k",
                "Min SLL k",
                "Min LL k",
                "Max SLL k",
                "Max LL k",
                "DFA k",
                "SLL-ATN k",
                "LL-ATN k",
                "Full context",
                "Ambiguities",
                "Predicates",
                "Exec",
            )

        val columnToolTips: Array<String> =
            arrayOf(
                "Decision index",
                DECISION_INVOCATIONS_TOOLTIP,
                "Rough estimate of time (ms) spent in prediction",
                "# DFA states",
                "# of SLL -> LL prediction failovers",
                "Total lookahead symbols examined",
                "Fewest SLL lookahead symbols examined in any decision event",
                "Fewest LL lookahead symbols examined in any decision event",
                "Max SLL lookahead symbols examined in any decision event",
                "Max LL lookahead symbols examined in any decision event",
                "# of DFA transitions during prediction (cache hit)",
                "# of conventional SLL ATN (non-DFA) transitions during prediction (cache miss)",
                "# of full-context LL ATN (non-DFA) transitions during prediction (cache miss)",
                "# of context-sensitive phrases found (not certain to be all)",
                "# of ambiguous input phrases",
                "# of predicate evaluations",
                RULE_EXECUTIONS_TOOLTIP,
            )
    }
}
