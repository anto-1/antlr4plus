package com.antlr4plus.filebinding

internal object RenameToG4pSelectionPolicy {
    fun isRenamable(isDirectory: Boolean, extension: String?): Boolean =
        !isDirectory && !extension.equals("g4", ignoreCase = true) && !extension.equals("g4p", ignoreCase = true)

    fun isGrammar(isDirectory: Boolean, extension: String?): Boolean =
        !isDirectory && extension.equals("g4", ignoreCase = true)
}
