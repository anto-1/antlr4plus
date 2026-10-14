package com.antlr4plus.parsing

import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.diagnostic.Logger
import java.io.File

internal fun PreviewService.reportBaseLanguageMismatch(
    files: List<File>,
    sourceLanguage: String,
    previewLanguage: String,
    log: Logger,
) {
    log.warn(
        "Language mismatch: ${files.size} $sourceLanguage base class file(s) cannot be used with $previewLanguage preview runtime",
    )
    compilerConsolePanel?.appendLine("Cannot compile preview: language mismatch.")
    compilerConsolePanel?.appendLine(
        "The grammar references base class(es) written in $sourceLanguage, but the preview runtime is set to $previewLanguage.",
    )
    compilerConsolePanel?.appendLine("")
    compilerConsolePanel?.appendLine("$sourceLanguage base class(es) found:")
    files.forEach { file ->
        compilerConsolePanel?.appendLine("  - ${file.absolutePath}")
    }
    compilerConsolePanel?.appendLine("")
    compilerConsolePanel?.appendLine(
        "To fix: right-click the .g4p file → Set Preview Runtime → $sourceLanguage.",
    )
    compilerConsolePanel?.appendLine(
        "(Or convert the base class(es) to $previewLanguage if you prefer the $previewLanguage runtime.)",
    )
    antlrConsolePanel?.appendLine(
        "Preview blocked: $sourceLanguage base class(es) require the $sourceLanguage preview runtime. See compiler console for details.",
    )
}

internal fun PreviewService.reportKotlinRuntimeMismatch(files: List<File>, log: Logger) {
    log.warn("Runtime mismatch: ${files.size} Kotlin base class(es) import the Java ANTLR runtime")
    compilerConsolePanel?.appendLine("Cannot compile preview: ANTLR runtime mismatch.")
    compilerConsolePanel?.appendLine(
        "Your Kotlin base class(es) import the Java ANTLR runtime (org.antlr.v4.runtime.*),",
    )
    compilerConsolePanel?.appendLine(
        "but the Kotlin preview uses the Kotlin port (org.antlr.v4.kotlinruntime.*).",
    )
    compilerConsolePanel?.appendLine(
        "These are separate codebases, not the same package; their types are unrelated.",
    )
    compilerConsolePanel?.appendLine("")
    compilerConsolePanel?.appendLine("Affected file(s):")
    files.forEach { file ->
        compilerConsolePanel?.appendLine("  - ${file.absolutePath}")
    }
    compilerConsolePanel?.appendLine("")
    compilerConsolePanel?.appendLine("To fix: change the imports in those file(s):")
    compilerConsolePanel?.appendLine(
        "  import org.antlr.v4.runtime.Lexer            →  import org.antlr.v4.kotlinruntime.Lexer",
    )
    compilerConsolePanel?.appendLine(
        "  import org.antlr.v4.runtime.CharStream       →  import org.antlr.v4.kotlinruntime.CharStream",
    )
    compilerConsolePanel?.appendLine(
        "  import org.antlr.v4.runtime.ParserRuleContext →  import org.antlr.v4.kotlinruntime.ParserRuleContext",
    )
    compilerConsolePanel?.appendLine("  (and any other org.antlr.v4.runtime.* imports)")
    antlrConsolePanel?.appendLine(
        "Preview blocked: Kotlin base class(es) use the wrong ANTLR runtime. See compiler console for the fix.",
    )
}
