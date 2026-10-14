package com.antlr4plus.context

import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/** Reads preview data and saved files in the order used for context export. */
internal class ContextContentCollector(private val project: Project) {

    private val previewService: PreviewService
        get() = project.service()

    /** Reuse these strings for section availability and export; this is not an atomic state snapshot. */
    fun collect(): Map<ContextSection, String> {
        val content = mutableMapOf<ContextSection, String>()

        fun addIfNotEmpty(section: ContextSection, producer: () -> String) {
            val value = producer()
            if (value.isNotEmpty()) content[section] = value
        }

        addIfNotEmpty(ContextSection.G4P_INPUT) { getG4pContent() }
        addIfNotEmpty(ContextSection.GRAMMAR_FILES) { getGrammarContent() }
        addIfNotEmpty(ContextSection.EXTERNAL_JAVA) { getExternalJavaContent() }
        addIfNotEmpty(ContextSection.PROFILER) { getProfilerContent() }
        addIfNotEmpty(ContextSection.LEXER_TOKENS) { getLexerContent() }
        addIfNotEmpty(ContextSection.HIERARCHY) { getHierarchyContent() }
        addIfNotEmpty(ContextSection.PARSER_ERRORS) { getParserErrorsContent() }
        addIfNotEmpty(ContextSection.ANTLR_ERRORS) { getAntlrErrorsContent() }
        addIfNotEmpty(ContextSection.JAVAC_ERRORS) { getJavacErrorsContent() }

        if (content.isNotEmpty()) {
            val summary = getSummaryContent()
            if (summary.isNotEmpty()) content[ContextSection.SUMMARY] = summary
        }

        return content
    }

    private fun getSummaryContent(): String = buildString {
        val state = previewService.state

        val parserGrammarFile = state.grammarFile
        val lexerGrammarFile = state.lexerGrammarFile
        if (parserGrammarFile != null) {
            appendLine("**Grammar:** `${parserGrammarFile.name}`")
            if (lexerGrammarFile != null && lexerGrammarFile.path != parserGrammarFile.path) {
                appendLine("**Lexer Grammar:** `${lexerGrammarFile.name}`")
            }
        }

        val g4pFile = state.g4pInputFile
        if (g4pFile != null) {
            appendLine("**Input File:** `${g4pFile.name}`")
            try {
                val content = runReadAction {
                    g4pFile.contentsToByteArray().toString(Charsets.UTF_8)
                }
                val lineCount = content.lines().size
                val charCount = content.length
                appendLine("**Input Size:** $lineCount lines, $charCount characters")
            } catch (_: Exception) {}
        }

        val startRule = state.startRuleName
        if (!startRule.isNullOrEmpty()) {
            appendLine("**Start Rule:** `$startRule`")
        }

        appendLine()

        val tokens = previewService.tokenTimelinePanel?.getTokensForExport()
        if (!tokens.isNullOrEmpty()) {
            val tokenCount = tokens.size
            val uniqueTypes = tokens.map { it.type }.distinct().size
            val hiddenCount = tokens.count { it.channel != 0 }
            appendLine(
                "**Tokens:** $tokenCount total, $uniqueTypes unique types, $hiddenCount off-channel",
            )
        }

        val hierarchy = previewService.hierarchy
        if (hierarchy != null) {
            val treeText = hierarchy.generateTreeText()
            val nodeCount = treeText.lines().count { it.isNotBlank() }
            appendLine("**Parse Tree:** $nodeCount nodes")
        }

        val parsingResult = state.parsingResult
        val syntaxErrorCount = parsingResult?.syntaxErrorListener?.syntaxErrors?.size ?: 0
        val antlrErrorCount = previewService.antlrConsolePanel?.getErrorsForExport()?.size ?: 0
        val javacErrorCount = previewService.compilerConsolePanel?.getErrorsForExport()?.size ?: 0

        if (syntaxErrorCount > 0 || antlrErrorCount > 0 || javacErrorCount > 0) {
            appendLine()
            appendLine("### Errors")
            if (syntaxErrorCount > 0) appendLine("- **Parser Errors:** $syntaxErrorCount")
            if (antlrErrorCount > 0) appendLine("- **ANTLR Errors:** $antlrErrorCount")
            if (javacErrorCount > 0) appendLine("- **Java Compiler Errors:** $javacErrorCount")
        } else {
            appendLine()
            appendLine("*No errors*")
        }

        val externalSourceFiles = state.activeExternalSourceFiles
        if (externalSourceFiles.isNotEmpty()) {
            appendLine()
            appendLine("**External Source Files:** ${externalSourceFiles.size}")
        }
    }

    private fun getG4pContent(): String = buildString {
        val g4pFile = previewService.state.g4pInputFile ?: return@buildString

        appendFileContent(g4pFile)
    }

    private fun getGrammarContent(): String = buildString {
        val state = previewService.state

        val parserGrammarFile = state.grammarFile
        if (parserGrammarFile != null) {
            appendLine("### Parser Grammar")
            appendLine()
            appendFileContent(parserGrammarFile, "antlr4")
            appendLine()
        }

        val lexerGrammarFile = state.lexerGrammarFile
        if (lexerGrammarFile != null && lexerGrammarFile.path != parserGrammarFile?.path) {
            appendLine("### Lexer Grammar")
            appendLine()
            appendFileContent(lexerGrammarFile, "antlr4")
            appendLine()
        }
    }

    private fun StringBuilder.appendFileContent(file: VirtualFile, language: String = "") {
        appendLine("**File:** `${file.path}`")
        appendLine()

        try {
            val content = runReadAction { file.contentsToByteArray().toString(Charsets.UTF_8) }
            appendLine("```$language")
            appendLine(content)
            appendLine("```")
        } catch (e: Exception) {
            appendLine("*Error reading file: ${e.message}*")
        }
    }

    private fun getExternalJavaContent(): String = buildString {
        val state = previewService.state

        val externalSourceFiles = state.activeExternalSourceFiles
        if (externalSourceFiles.isEmpty()) return@buildString

        externalSourceFiles.forEachIndexed { index, filePath ->
            val file = File(filePath)
            if (file.exists()) {
                appendLine("### ${index + 1}. ${file.name}")
                appendLine()
                appendLine("**File:** `$filePath`")
                appendLine()
                try {
                    val content = file.readText()
                    val lang = if (file.extension.equals("kt", ignoreCase = true)) "kotlin" else "java"
                    appendLine("```$lang")
                    appendLine(content)
                    appendLine("```")
                } catch (e: Exception) {
                    appendLine("*Error reading file: ${e.message}*")
                }
                appendLine()
            }
        }
    }

    private fun getProfilerContent(): String = previewService.composeProfilerPanel?.getContextAsMarkdown().orEmpty()

    private fun getLexerContent(): String {
        val tokenTimelinePanel = previewService.tokenTimelinePanel ?: return ""

        val tokens = tokenTimelinePanel.getTokensForExport() ?: return ""
        if (tokens.isEmpty()) return ""

        return lexerTokensMarkdown(tokens)
    }

    private fun getHierarchyContent(): String {
        val hierarchy = previewService.hierarchy ?: return ""
        return "```\n${hierarchy.generateTreeText()}```"
    }

    private fun getParserErrorsContent(): String {
        val state = previewService.state

        val parsingResult = state.parsingResult ?: return ""
        val syntaxErrors = parsingResult.syntaxErrorListener?.syntaxErrors ?: return ""
        if (syntaxErrors.isEmpty()) return ""

        return parserErrorsMarkdown(syntaxErrors)
    }

    private fun getAntlrErrorsContent(): String {
        val antlrPanel = previewService.antlrConsolePanel ?: return ""
        val errors = antlrPanel.getErrorsForExport() ?: return ""
        if (errors.isEmpty()) return ""

        return consoleErrorsMarkdown(errors)
    }

    private fun getJavacErrorsContent(): String {
        val compilerPanel = previewService.compilerConsolePanel ?: return ""
        val errors = compilerPanel.getErrorsForExport() ?: return ""
        if (errors.isEmpty()) return ""

        return consoleErrorsMarkdown(errors)
    }
}
