package com.antlr4plus.dotG4Support.formatting

/** Fixed grammar formatting defaults. */
class ANTLRv4FormattingSettings {
    val alignColons: ColonAlignmentMode = ColonAlignmentMode.HANGING
    val alignSemicolons: SemicolonAlignmentMode = SemicolonAlignmentMode.HANGING
    val alignTrailingComments: Boolean = true
    val alignLexerCommands: Boolean = false

    val indentWidth: Int = 4
    val useTab: Boolean = false

    val inlineSingleAlternativeRules: Boolean = true
}

enum class ColonAlignmentMode {
    NONE,
    TRAILING,
    HANGING,
}

enum class SemicolonAlignmentMode {
    NONE,
    OWN_LINE,
    HANGING,
}
