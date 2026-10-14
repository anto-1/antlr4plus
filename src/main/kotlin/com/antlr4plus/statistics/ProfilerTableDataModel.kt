package com.antlr4plus.statistics

import java.math.BigDecimal
import java.math.RoundingMode
import javax.swing.table.AbstractTableModel
import org.antlr.v4.runtime.atn.ParseInfo

abstract class ProfilerTableDataModel : AbstractTableModel() {
    abstract val columnNames: Array<String>
    abstract val columnToolTips: Array<String>

    override fun getColumnCount(): Int = columnNames.size

    override fun getColumnName(column: Int): String = columnNames[column]

    fun getColumnToolTip(column: Int): String = columnToolTips.getOrElse(column) { "" }

    abstract override fun getRowCount(): Int

    abstract override fun getValueAt(rowIndex: Int, columnIndex: Int): Any
}

/** Keeps profiler prediction times proportional to a positive measured total. */
internal fun ParseInfo.predictionTimeNormalizationFactor(
    expectedTotalPredictionTimeMs: Double?,
): Double =
    if (expectedTotalPredictionTimeMs != null && expectedTotalPredictionTimeMs > 0) {
        val rawSum =
            decisionInfo.indices.sumOf {
                decisionInfo[it].timeInPrediction
            } / 1_000_000.0
        if (rawSum > 0) expectedTotalPredictionTimeMs / rawSum else 1.0
    } else {
        1.0
    }

internal const val DECISION_INVOCATIONS_TOOLTIP =
    "\"#\" (Decision Invocations): Every time the parser needs to choose between " +
        "alternatives or decide whether to continue/exit a loop, that's one decision invocation."

internal const val RULE_EXECUTIONS_TOOLTIP =
    "\"Exec\" (Rule Executions): Every time the parser enters a rule, one context node " +
        "is created in the parse tree. This counts those nodes.\n\n" +
        "Why they differ:\n" +
        "A single rule can contain multiple decision points (loops, optionals, alternatives). " +
        "When you enter the rule once, Exec = 1. But if that rule has a loop that repeats " +
        "10 times, the parser makes 11 decisions (10 \"continue\" + 1 \"exit\"), so # = 11.\n\n" +
        "In short:\n" +
        "  - Exec = how many times the rule was entered\n" +
        "  - # = how many times decisions inside that rule were evaluated\n\n" +
        "Note: If you see # = 0 but Exec > 0, this means the decision is LL(1). " +
        "Compiled parsers use a direct switch statement for LL(1) decisions instead of " +
        "calling adaptivePredict(), so the profiler doesn't record them.\n\n" +
        "Conversely, if # >> Exec (e.g., # = 13, Exec = 1), the rule contains a loop. " +
        "You entered the rule once, but the loop iterated 12 times, causing 13 decisions " +
        "(12 \"continue\" + 1 \"exit\")."

/** Converts nanoseconds to normalized milliseconds using the table's four-decimal rounding. */
internal fun Long.normalizedPredictionTimeMs(timeNormalizationFactor: Double): BigDecimal =
    BigDecimal.valueOf(this / 1_000_000.0 * timeNormalizationFactor).setScale(4, RoundingMode.HALF_DOWN)
