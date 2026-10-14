package com.antlr4plus.settings

import com.antlr4plus.parsing.CaseChangingStrategy

/** One coherent value for change detection and persistence, grouped by UI domain. */
internal data class LiveSettingsSnapshot(
    val generation: GenerationSettingsSnapshot,
    val profiler: ProfilerSettingsSnapshot,
    val tree: TreeSettingsSnapshot,
    val formatting: FormattingSettingsSnapshot,
) {
    fun applyTo(state: State) {
        generation.applyTo(state)
        profiler.applyTo(state)
        tree.applyTo(state)
        formatting.applyTo(state)
    }
}

internal data class GenerationSettingsSnapshot(
    val outputDir: String?,
    val libDir: String?,
    val encoding: String?,
    val packageName: String?,
    val language: String?,
    val createLanguageSubdir: Boolean,
    val previewRuntime: String?,
    val caseChangingStrategy: CaseChangingStrategy,
    val generateListener: Boolean,
    val generateVisitor: Boolean,
) {
    fun applyTo(state: State) {
        state.outputDir = outputDir ?: "gen"
        state.libDir = libDir ?: ""
        state.encoding = encoding ?: "UTF-8"
        state.packageName = packageName ?: ""
        state.language = language ?: "Java"
        state.createLanguageSubdir = createLanguageSubdir
        state.previewRuntime = previewRuntime ?: "Java"
        state.caseChangingStrategy = caseChangingStrategy
        state.generateListener = generateListener
        state.generateVisitor = generateVisitor
    }
}

internal data class ProfilerSettingsSnapshot(
    val simpleProfilerColumnWidths: String?,
    val expertProfilerColumnWidths: String?,
    val benchmarkWarmupDurationMs: Long,
    val benchmarkMeasurementDurationMs: Long,
    val parsingTimeoutMs: Long,
) {
    fun applyTo(state: State) {
        state.simpleProfilerColumnWidths = simpleProfilerColumnWidths
        state.expertProfilerColumnWidths = expertProfilerColumnWidths
        state.benchmarkWarmupDurationMs = benchmarkWarmupDurationMs
        state.benchmarkMeasurementDurationMs = benchmarkMeasurementDurationMs
        state.parsingTimeoutMs = parsingTimeoutMs
    }
}

internal data class TreeSettingsSnapshot(
    val treeConnectionMode: String?,
    val treeEdgeColor: String?,
    val treeNodeColor: String?,
    val treeTextColor: String?,
    val treeBackgroundColor: String?,
) {
    fun applyTo(state: State) {
        state.treeConnectionMode = treeConnectionMode
        state.treeEdgeColor = treeEdgeColor
        state.treeNodeColor = treeNodeColor
        state.treeTextColor = treeTextColor
        state.treeBackgroundColor = treeBackgroundColor
    }
}

internal data class FormattingSettingsSnapshot(
    val formattingPreset: String?,
    val alignColons: String?,
    val alignSemicolons: String?,
    val alignTrailingComments: Boolean,
    val alignLexerCommands: Boolean,
    val formattingIndentWidth: Int,
    val formattingUseTab: Boolean,
    val formattingColumnLimit: Int,
    val allowShortRulesOnASingleLine: Boolean,
) {
    fun applyTo(state: State) {
        state.formattingPreset = formattingPreset
        state.alignColons = alignColons
        state.alignSemicolons = alignSemicolons
        state.alignTrailingComments = alignTrailingComments
        state.alignLexerCommands = alignLexerCommands
        state.formattingIndentWidth = formattingIndentWidth
        state.formattingUseTab = formattingUseTab
        state.formattingColumnLimit = formattingColumnLimit
        state.allowShortRulesOnASingleLine = allowShortRulesOnASingleLine
    }
}
