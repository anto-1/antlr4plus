package com.antlr4plus.benchmark

import com.antlr4plus.benchmark.BenchmarkRunner.BenchmarkResult
import com.antlr4plus.benchmark.BenchmarkRunner.ProfilerStats
import kotlin.math.sqrt
import org.antlr.v4.runtime.Parser

private data class ProfilerStatsResult(
    val predictionTimeMs: Double,
    val predictionPercent: Double,
    val lookaheadBurden: Double,
    val cacheMissRate: Double,
    val numTokens: Int,
)

internal fun calculateBenchmarkStatistics(
    name: String,
    times: List<Long>,
    profilerStats: List<ProfilerStats>,
    inputChars: Int,
    inputLines: Int,
    usedCompiledClasses: Boolean,
    lastParserAny: Any? = null,
    isKotlin: Boolean = false,
): BenchmarkResult {
    val sorted = times.sorted()
    val n = times.size

    val mean = times.average()
    val median =
        if (n % 2 == 0) {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        } else {
            sorted[n / 2].toDouble()
        }

    val variance = times.map { (it - mean) * (it - mean) }.average()
    val stdDev = sqrt(variance)
    val cv = if (mean > 0) stdDev / mean else 0.0

    val min = sorted.first()
    val max = sorted.last()

    val throughput = 1_000_000_000.0 / mean

    val lastParser: Parser? = if (!isKotlin && lastParserAny is Parser) lastParserAny else null

    val (avgPredictionTimeMs, avgPredictionPercent, avgLookaheadBurden, avgCacheMissRate, numTokens) =
        calculateProfilerStatistics(profilerStats, mean, lastParser)

    return BenchmarkResult(
        name = name,
        iterations = n,
        meanNs = mean,
        medianNs = median,
        stdDevNs = stdDev,
        minNs = min,
        maxNs = max,
        throughputOpsPerSec = throughput,
        allTimesNs = times,
        coefficientOfVariation = cv,
        inputChars = inputChars,
        inputLines = inputLines,
        usedCompiledClasses = usedCompiledClasses,
        avgPredictionTimeMs = avgPredictionTimeMs,
        avgPredictionPercent = avgPredictionPercent,
        avgLookaheadBurden = avgLookaheadBurden,
        avgCacheMissRate = avgCacheMissRate,
        numTokens = numTokens,
        lastParser = lastParser,
    )
}

private fun calculateProfilerStatistics(
    profilerStats: List<ProfilerStats>,
    mean: Double,
    lastParser: Parser?,
): ProfilerStatsResult =
    if (profilerStats.isNotEmpty()) {
        val predictionTimeMs = profilerStats.map { it.predictionTimeMs }.average()
        val predictionPercent = if (mean > 0) (predictionTimeMs / (mean / 1_000_000.0)) * 100 else 0.0
        val lookaheadBurden = profilerStats.map { it.lookaheadBurden }.average()
        val cacheMissRate = profilerStats.map { it.cacheMissRate }.average()
        val tokens = profilerStats.firstOrNull()?.numTokens ?: 0
        ProfilerStatsResult(
            predictionTimeMs,
            predictionPercent,
            lookaheadBurden,
            cacheMissRate,
            tokens,
        )
    } else {
        // Fall back to the last Java parser when no per-iteration statistics were collected.
        val parseInfo = lastParser?.parseInfo
        if (parseInfo != null) {
            val totalLookahead = parseInfo.totalSLLLookaheadOps + parseInfo.totalLLLookaheadOps
            val tokens = lastParser.inputStream?.size() ?: 0
            val predictionTimeMs = parseInfo.totalTimeInPrediction / 1_000_000.0
            val predictionPercent = if (mean > 0) (predictionTimeMs / (mean / 1_000_000.0)) * 100 else 0.0
            val lookaheadBurden = if (tokens > 0) totalLookahead.toDouble() / tokens else 0.0
            val cacheMissRate = if (totalLookahead > 0) parseInfo.totalATNLookaheadOps * 100.0 / totalLookahead else 0.0
            ProfilerStatsResult(
                predictionTimeMs,
                predictionPercent,
                lookaheadBurden,
                cacheMissRate,
                tokens,
            )
        } else {
            ProfilerStatsResult(0.0, 0.0, 0.0, 0.0, 0)
        }
    }
