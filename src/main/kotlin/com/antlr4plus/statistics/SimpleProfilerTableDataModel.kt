package com.antlr4plus.statistics

import kotlin.math.max
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.atn.ParseInfo

class SimpleProfilerTableDataModel(
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

    override fun getRowCount(): Int = parseInfo.decisionInfo.size

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val decisionInfo = parseInfo.decisionInfo[rowIndex]
        return when (columnIndex) {
            0 -> String.format("%s (%d)", ruleNamesByDecision[rowIndex], rowIndex)
            1 -> manualInvocations?.get(rowIndex) ?: decisionInfo.invocations
            2 -> decisionInfo.timeInPrediction.normalizedPredictionTimeMs(timeNormalizationFactor)
            3 -> decisionInfo.LL_TotalLook + decisionInfo.SLL_TotalLook
            4 -> max(decisionInfo.LL_MaxLook, decisionInfo.SLL_MaxLook)
            5 -> decisionInfo.ambiguities.size
            6 -> decisionInfo.SLL_ATNTransitions + decisionInfo.LL_ATNTransitions
            7 -> ruleExecutions?.get(rowIndex) ?: 0L
            else -> "n/a"
        }
    }

    override val columnNames: Array<String>
        get() = Companion.columnNames

    override val columnToolTips: Array<String>
        get() = Companion.columnToolTips

    companion object {
        val columnNames: Array<String> =
            arrayOf(
                "Rule",
                "#",
                "ms",
                "Total k",
                "Max k",
                "Ambiguities",
                "DFA cache miss",
                "Exec",
            )

        val columnToolTips: Array<String> =
            arrayOf(
                "name of rule and decision no",
                DECISION_INVOCATIONS_TOOLTIP,
                "Rough estimate of time (ms) spent in prediction",
                "Total lookahead symbols examined",
                "Max lookahead symbols examined in any decision event",
                "# of ambiguous input phrases",
                "# of non-DFA transitions during prediction (cache miss)",
                RULE_EXECUTIONS_TOOLTIP,
            )
    }

    class EmptyModel : ProfilerTableDataModel() {
        override val columnNames: Array<String>
            get() = SimpleProfilerTableDataModel.columnNames

        override val columnToolTips: Array<String>
            get() = SimpleProfilerTableDataModel.columnToolTips

        override fun getRowCount(): Int = 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = "n/a"
    }
}
