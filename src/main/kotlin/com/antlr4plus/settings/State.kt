package com.antlr4plus.settings

import com.antlr4plus.parsing.CaseChangingStrategy
import com.intellij.openapi.components.BaseState

class State : BaseState() {
    var outputDir by string()
    var libDir by string()
    var encoding by string()
    var packageName by string()
    var language by string()
    var createLanguageSubdir by property(true) // Include the target-language subdirectory in generated output.
    var previewRuntime by string() // Java or Kotlin; null uses Java.
    var caseChangingStrategy by enum(CaseChangingStrategy.LEAVE_AS_IS)
    var generateListener by property(true)
    var generateVisitor by property(true)
    var simpleProfilerColumnWidths by string() // Comma-separated column widths.
    var expertProfilerColumnWidths by string() // Comma-separated column widths.

    var benchmarkWarmupDurationMs by property(500L)
    var benchmarkMeasurementDurationMs by property(1000L)
    var parsingTimeoutMs by property(2000L) // Benchmark deadline checked between parse iterations.

    var treeConnectionMode by string() // SPLINE, ORTHOGONAL or DIRECT; null uses SPLINE.
    var treeEdgeColor by string() // Hex color; null uses the default.
    var treeNodeColor by string() // Hex color; null uses the theme default.
    var treeTextColor by string() // Hex color; null uses the default.
    var treeBackgroundColor by string() // Hex color; null uses the theme default.

    var formattingPreset by string() // compact, expanded, hanging or custom.
    var alignColons by string() // none, trailing or hanging.
    var alignSemicolons by string() // none, ownLine or hanging.
    var alignTrailingComments by property(true)
    var alignLexerCommands by property(false)
    var formattingIndentWidth by property(4)
    var formattingUseTab by property(false)
    var formattingColumnLimit by property(150)
    var allowShortRulesOnASingleLine by property(false)
}
