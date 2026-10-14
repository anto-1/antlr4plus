package com.antlr4plus.benchmark

import com.antlr4plus.benchmark.BenchmarkRunner.ProfilerStats
import com.antlr4plus.util.rethrowFatalOrCancellation
import com.intellij.openapi.diagnostic.Logger
import org.antlr.v4.runtime.Parser

internal fun extractProfilerStats(parser: Any, isKotlin: Boolean, log: Logger): ProfilerStats? =
    if (isKotlin) {
        extractKotlinProfilerStats(parser, log)
    } else {
        extractJavaProfilerStats(parser as Parser)
    }

private fun extractJavaProfilerStats(parser: Parser): ProfilerStats? =
    parser.parseInfo?.let { parseInfo ->
        ProfilerStats(
            totalPredictionTimeNs = parseInfo.totalTimeInPrediction,
            totalSLLLookaheadOps = parseInfo.totalSLLLookaheadOps,
            totalLLLookaheadOps = parseInfo.totalLLLookaheadOps,
            totalATNLookaheadOps = parseInfo.totalATNLookaheadOps,
            numTokens = parser.inputStream?.size() ?: 0,
        )
    }

private fun extractKotlinProfilerStats(parser: Any, log: Logger): ProfilerStats? {
    return try {
        val getParseInfoMethod = parser.javaClass.getMethod("getParseInfo")
        val parseInfo = getParseInfoMethod.invoke(parser) ?: return null

        val totalTimeInPrediction =
            parseInfo.javaClass.getMethod("getTotalTimeInPrediction").invoke(parseInfo) as? Long ?: 0L
        val totalSLLLookaheadOps =
            parseInfo.javaClass.getMethod("getTotalSLLLookaheadOps").invoke(parseInfo) as? Long ?: 0L
        val totalLLLookaheadOps =
            parseInfo.javaClass.getMethod("getTotalLLLookaheadOps").invoke(parseInfo) as? Long ?: 0L
        val totalATNLookaheadOps =
            parseInfo.javaClass.getMethod("getTotalATNLookaheadOps").invoke(parseInfo) as? Long ?: 0L

        val numTokens =
            try {
                val getTokenStreamMethod = parser.javaClass.getMethod("getTokenStream")
                val tokenStream = getTokenStreamMethod.invoke(parser)
                val sizeMethod = tokenStream?.javaClass?.getMethod("size")
                sizeMethod?.invoke(tokenStream) as? Int ?: 0
            } catch (e: Exception) {
                rethrowFatalOrCancellation(e)
                0
            }

        ProfilerStats(
            totalPredictionTimeNs = totalTimeInPrediction,
            totalSLLLookaheadOps = totalSLLLookaheadOps,
            totalLLLookaheadOps = totalLLLookaheadOps,
            totalATNLookaheadOps = totalATNLookaheadOps,
            numTokens = numTokens,
        )
    } catch (e: Exception) {
        rethrowFatalOrCancellation(e)
        log.debug("Could not extract Kotlin profiler stats: ${e.message}")
        null
    }
}
