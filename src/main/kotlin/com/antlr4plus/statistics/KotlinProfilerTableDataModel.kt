package com.antlr4plus.statistics

import com.antlr4plus.parsing.ParsingResult.KotlinDecisionInfo
import com.intellij.openapi.diagnostic.Logger
import kotlin.math.max
import org.antlr.v4.runtime.tree.Tree

private val LOG = Logger.getInstance(KotlinProfilerTableDataModel::class.java)

/** Displays reflected Kotlin-runtime profiling data. */
class KotlinProfilerTableDataModel(
    private val decisionInfoList: List<KotlinDecisionInfo>,
    private val ruleNames: Array<String>,
    private val ruleIndexByDecision: IntArray,
    expectedTotalPredictionTimeMs: Double? = null,
    private val ruleExecutions: Map<Int, Long>? = null,
) : ProfilerTableDataModel() {

    private val timeNormalizationFactor: Double =
        if (expectedTotalPredictionTimeMs != null && expectedTotalPredictionTimeMs > 0) {
            val rawSum = decisionInfoList.sumOf { it.timeInPrediction } / 1_000_000.0
            if (rawSum > 0) expectedTotalPredictionTimeMs / rawSum else 1.0
        } else {
            1.0
        }

    override fun getRowCount(): Int = decisionInfoList.size

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        if (rowIndex >= decisionInfoList.size) return "n/a"
        val info = decisionInfoList[rowIndex]
        val ruleIndex = ruleIndexByDecision.getOrNull(rowIndex) ?: -1
        val ruleName = if (ruleIndex >= 0 && ruleIndex < ruleNames.size) ruleNames[ruleIndex] else "?"

        return when (columnIndex) {
            0 -> String.format("%s (%d)", ruleName, info.decision)
            1 -> info.invocations
            2 -> info.timeInPrediction.normalizedPredictionTimeMs(timeNormalizationFactor)
            3 -> info.llTotalLook + info.sllTotalLook
            4 -> max(info.llMaxLook, info.sllMaxLook)
            5 -> info.ambiguitiesCount
            6 -> info.sllATNTransitions + info.llATNTransitions
            7 -> ruleExecutions?.get(info.decision) ?: 0L
            else -> "n/a"
        }
    }

    override val columnNames: Array<String>
        get() = SimpleProfilerTableDataModel.columnNames

    override val columnToolTips: Array<String>
        get() = SimpleProfilerTableDataModel.columnToolTips

    companion object {
        /** Returns null for non-Kotlin results, missing decision data or missing rule names. */
        fun create(
            parsingResult: com.antlr4plus.parsing.ParsingResult,
            expectedTotalPredictionTimeMs: Double? = null,
            javaTree: Tree? = null,
        ): KotlinProfilerTableDataModel? {
            LOG.info(
                "KotlinProfilerTableDataModel.create: isKotlin=${parsingResult.isKotlin}, javaTree=${javaTree?.javaClass?.simpleName}",
            )
            if (!parsingResult.isKotlin) return null

            val decisionInfoList = parsingResult.getKotlinDecisionInfoList()
            LOG.info("  decisionInfoList: ${decisionInfoList?.size ?: "null"}")
            if (decisionInfoList.isNullOrEmpty()) return null

            val ruleNames = parsingResult.ruleNames
            LOG.info("  ruleNames: ${ruleNames?.size ?: "null"}")
            if (ruleNames == null) return null

            val ruleIndexByDecision = getKotlinRuleIndexByDecision(parsingResult.kotlinParser, decisionInfoList.size)
            LOG.info("  ruleIndexByDecision sample: ${ruleIndexByDecision.take(5).toList()}")

            val ruleExecutions =
                countRuleExecutionsFromKotlinTree(
                    javaTree,
                    ruleIndexByDecision,
                    decisionInfoList.size,
                    LOG,
                )
            LOG.info("  ruleExecutions: ${ruleExecutions?.size ?: "null"}")

            return KotlinProfilerTableDataModel(
                decisionInfoList,
                ruleNames,
                ruleIndexByDecision,
                expectedTotalPredictionTimeMs,
                ruleExecutions,
            )
        }
    }
}
