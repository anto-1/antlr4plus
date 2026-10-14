package com.antlr4plus.context

import com.antlr4plus.timeline.TokenWithMode
import com.antlr4plus.util.escapeWhitespace
import java.text.SimpleDateFormat
import java.util.Date
import org.antlr.intellij.adaptor.parser.SyntaxError

@OptIn(ExperimentalStdlibApi::class)
internal fun contextMarkdown(
    selectedSections: Set<ContextSection>,
    contentMap: Map<ContextSection, String>,
): String = buildString {
    val sections = mutableListOf<Pair<ContextSection, String>>()

    for (section in ContextSection.entries) {
        if (section !in selectedSections) continue
        val content = contentMap[section]
        if (!content.isNullOrEmpty()) {
            sections.add(section to content)
        }
    }

    if (sections.isEmpty()) return@buildString

    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
    appendLine("# ANTLR4+ Context Export")
    appendLine()
    appendLine("*Generated: $timestamp*")
    appendLine()

    if (sections.size > 1) {
        appendLine("## Table of Contents")
        appendLine()
        sections.forEachIndexed { index, (section, _) ->
            val anchor = section.displayName.lowercase().replace(" ", "-")
            appendLine("${index + 1}. [${section.displayName}](#$anchor)")
        }
        appendLine()
        appendLine("---")
        appendLine()
    }

    sections.forEachIndexed { index, (section, content) ->
        appendLine("## ${section.displayName}")
        appendLine()
        append(content)
        appendLine()
        if (index < sections.size - 1) {
            appendLine("---")
            appendLine()
        }
    }
}

internal fun lexerTokensMarkdown(tokens: List<TokenWithMode>): String = buildString {
    appendLine("| # | Type | Text | Line | Col | Channel |")
    appendLine("|---|------|------|------|-----|---------|")

    tokens.forEachIndexed { index, token ->
        val text = escapeMarkdown(escapeWhitespace(token.text))
        val type = token.type.toString()
        val line = token.line
        val col = token.charPositionInLine
        val channel = token.channel
        appendLine("| $index | $type | $text | $line | $col | $channel |")
    }
}

internal fun parserErrorsMarkdown(syntaxErrors: List<SyntaxError>): String = buildString {
    appendLine("| Line | Col | Message |")
    appendLine("|------|-----|---------|")

    syntaxErrors.forEach { error ->
        val line = error.line
        val col = error.charPositionInLine
        val msg = escapeMarkdown(error.message ?: "Unknown error")
        appendLine("| $line | $col | $msg |")
    }
}

internal fun consoleErrorsMarkdown(errors: List<String>): String = buildString {
    appendLine("```")
    errors.forEach { appendLine(it) }
    appendLine("```")
}

private fun escapeMarkdown(text: String): String = text.replace("|", "\\|").replace("\n", " ").replace("\r", "")
