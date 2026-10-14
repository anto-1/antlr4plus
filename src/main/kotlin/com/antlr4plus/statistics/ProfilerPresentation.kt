package com.antlr4plus.statistics

import com.antlr4plus.benchmark.BenchmarkRunner

internal fun profilerMarkdown(
    tableModel: ProfilerTableDataModel,
    result: BenchmarkRunner.BenchmarkResult?,
    initial: ComposeProfilerPanel.InitialStats?,
): String = buildString {
    appendLine("### Parse Statistics")
    appendLine()
    if (result != null) {
        appendLine("| Metric | Value |")
        appendLine("|--------|-------|")
        appendLine(
            "| Parse time (ms) | ${String.format("%.3f ±%.3f", result.meanMs, result.stdDevMs)} |",
        )
        appendLine("| Input size | ${result.inputChars} char, ${result.inputLines} lines |")
        appendLine("| Number of tokens | ${result.numTokens} |")
        appendLine(
            "| Prediction time (ms) | ${String.format("%.3f = %.2f%%", result.avgPredictionTimeMs, result.avgPredictionPercent)} |",
        )
        appendLine("| Lookahead burden | ${String.format("%.2f", result.avgLookaheadBurden)} |")
        appendLine("| DFA cache miss rate | ${String.format("%.2f%%", result.avgCacheMissRate)} |")
        appendLine("| Benchmark iterations | ${result.iterations} |")
    } else if (initial != null) {
        appendLine("| Metric | Value |")
        appendLine("|--------|-------|")
        appendLine("| Parse time (ms) | ${String.format("%.3f", initial.parseTimeMs)} |")
        appendLine("| Input size | ${initial.inputChars} char, ${initial.inputLines} lines |")
        appendLine("| Number of tokens | ${initial.numTokens} |")
        appendLine(
            "| Prediction time (ms) | ${String.format("%.3f = %.2f%%", initial.predictionTimeMs, initial.predictionPercent)} |",
        )
        appendLine("| Lookahead burden | ${String.format("%.2f", initial.lookaheadBurden)} |")
        appendLine("| DFA cache miss rate | ${String.format("%.2f%%", initial.cacheMissRate)} |")
    }
    appendLine()

    appendDecisionTable(tableModel)
}

private fun StringBuilder.appendDecisionTable(tableModel: ProfilerTableDataModel) {
    appendLine("### Decision Table")
    appendLine()

    if (tableModel.rowCount > 0) {
        append("| ")
        append(tableModel.columnNames.joinToString(" | "))
        appendLine(" |")

        append("|")
        repeat(tableModel.columnNames.size) { append("------|") }
        appendLine()

        val msColIdx = tableModel.columnNames.indexOfFirst { it == "ms" }
        val rowIndices = (0 until tableModel.rowCount).toMutableList()
        if (msColIdx >= 0) {
            rowIndices.sortByDescending {
                (tableModel.getValueAt(it, msColIdx) as? Number)?.toDouble() ?: 0.0
            }
        }

        for (rowIdx in rowIndices) {
            append("| ")
            for (col in 0 until tableModel.columnNames.size) {
                val value = tableModel.getValueAt(rowIdx, col)
                append(value.toString())
                if (col < tableModel.columnNames.size - 1) append(" | ")
            }
            appendLine(" |")
        }
    } else {
        appendLine("*No profiler data available*")
    }
}

internal fun profilerColumnHelpText(columnName: String, basicTooltip: String): String =
    when (columnName) {
        "#" ->
            """
            Decision invocations - how many times the parser evaluated this decision point in the ATN (Augmented Transition Network).

            This is different from rule invocations:
            • A decision is a choice point (e.g., value: dict | list | string has 3 alternatives)
            • For loops like value*, the decision is invoked N+1 times: once per iteration plus once to exit
            • A rule may contain multiple decisions

            A value of 0 means:
            • The decision point was never reached during parsing (e.g., the rule was never invoked)
            • Or for empty loops (like items*), the loop exited immediately without any iterations
            """
                .trimIndent()

        "Rule" ->
            """
            The rule name containing this decision, followed by the decision index in parentheses.

            Click a row to highlight the decision in the grammar file. Long-press to highlight matching tokens in the input file.
            """
                .trimIndent()

        "ms" ->
            """
            Rough estimate of time spent in prediction for this decision.

            This measures the time ANTLR's prediction algorithm spent determining which alternative to take. High values may indicate complex or ambiguous grammar constructs.
            """
                .trimIndent()

        "Total k" ->
            """
            Total lookahead symbols examined across all invocations of this decision.

            This is the SUM of lookahead tokens across all invocations. A high value may simply mean many invocations occurred, each with low lookahead.

            Example: Total k = 10 could mean 10 invocations with k=1 each (efficient) or 1 invocation with k=10 (inefficient). Check "Max k" to distinguish.

            Note: Row coloring is based on Max k, not Total k.
            """
                .trimIndent()

        "Max k" ->
            """
            Maximum lookahead depth in any single prediction for this decision.

            This is the DEEPEST lookahead required in a single invocation - the true measure of decision complexity.

            • Max k = 1: All predictions resolved by looking at just the current token (optimal)
            • Max k > 1: Parser had to look further ahead to decide (potential optimization target)

            Rows are colored teal when Max k > 1, indicating decisions that required deeper lookahead.
            """
                .trimIndent()

        "Ambiguities" ->
            """
            Number of ambiguous input phrases detected for this decision.

            An ambiguity occurs when multiple grammar alternatives match the same input. While ANTLR resolves these by choosing the first alternative, ambiguities may indicate grammar design issues.
            """
                .trimIndent()

        "DFA cache miss" ->
            """
            Number of ATN transitions during prediction (cache misses).

            ANTLR caches prediction results in a DFA. Cache misses require walking the ATN, which is slower. High values after warmup may indicate grammar complexity.
            """
                .trimIndent()

        "# DFA states" ->
            """
            Number of DFA states created for this decision.

            More states generally means more complex decision logic. Very high values may indicate exponential DFA growth from ambiguous grammar patterns.
            """
                .trimIndent()

        "LL failover" ->
            """
            Number of SLL to LL prediction failovers.

            ANTLR first tries fast SLL prediction. When SLL can't resolve the decision, it falls back to slower LL prediction with full context. High values may impact performance.
            """
                .trimIndent()

        "Full context" ->
            """
            Number of context-sensitive phrases found.

            These are cases where the parser needed to consider the calling context (parse stack) to make a decision. May indicate complex grammar interactions.
            """
                .trimIndent()

        "Predicates" ->
            """
            Number of semantic predicate evaluations.

            Predicates are user-defined conditions in the grammar that influence parsing decisions. Frequent evaluations may impact performance.
            """
                .trimIndent()

        else -> basicTooltip
    }
