package com.antlr4plus.parsing.actionMap

import com.antlr4plus.parsing.embedded.EmbeddedCode
import java.io.File

/** Locates generated class, rule, and exception-clause bodies for action mapping. */
internal object GeneratedActionScopes {

    data class TextWindow(
        val startOffset: Int,
        val endOffsetExclusive: Int,
    )

    data class ScopeKey(
        val type: EmbeddedCode.CodeType,
        val scopeName: String?,
        val contextInfo: String?,
    )

    fun findSearchWindows(
        action: EmbeddedCode,
        generatedFile: File,
        generatedText: String,
        scopeWindowCache: MutableMap<ScopeKey, List<TextWindow>>,
    ): List<TextWindow> {
        val windows = findActionWindows(action, generatedFile, generatedText, scopeWindowCache)
        return if (windows.isNotEmpty() || requiresDedicatedSearchWindow(action.type)) {
            windows
        } else {
            listOf(TextWindow(0, generatedText.length))
        }
    }

    private fun findActionWindows(
        action: EmbeddedCode,
        generatedFile: File,
        generatedText: String,
        scopeWindowCache: MutableMap<ScopeKey, List<TextWindow>>,
    ): List<TextWindow> {
        val scopeKey = ScopeKey(action.type, action.scopeName, action.contextInfo)
        val methodWindows =
            scopeWindowCache.getOrPut(scopeKey) {
                findScopeWindows(action, generatedFile, generatedText)
            }
        if (methodWindows.isEmpty()) return emptyList()

        return when (action.type) {
            EmbeddedCode.CodeType.MEMBERS,
            EmbeddedCode.CodeType.SCOPED_MEMBERS -> methodWindows

            EmbeddedCode.CodeType.ARGUMENT_BLOCK ->
                when {
                    action.contextInfo?.startsWith("call to ") == true ->
                        methodWindows.map {
                            extractTryWindow(generatedText, it) ?: it
                        }
                    else -> methodWindows
                }

            EmbeddedCode.CodeType.PARSER_ACTION,
            EmbeddedCode.CodeType.SEMANTIC_PREDICATE ->
                methodWindows.map {
                    extractTryWindow(generatedText, it) ?: it
                }

            EmbeddedCode.CodeType.RULE_INIT ->
                methodWindows.map {
                    val tryWindow = extractTryWindow(generatedText, it)
                    if (tryWindow != null && tryWindow.startOffset > it.startOffset) {
                        TextWindow(it.startOffset, tryWindow.startOffset)
                    } else {
                        it
                    }
                }

            EmbeddedCode.CodeType.EXCEPTION_HANDLER ->
                methodWindows.flatMap {
                    extractCatchWindows(generatedText, it)
                }

            EmbeddedCode.CodeType.RULE_AFTER ->
                methodWindows.map {
                    extractFinallyWindow(generatedText, it) ?: extractTryWindow(generatedText, it) ?: it
                }

            EmbeddedCode.CodeType.FINALLY_CLAUSE ->
                methodWindows.mapNotNull {
                    extractFinallyWindow(generatedText, it)
                }

            EmbeddedCode.CodeType.LEXER_ACTION -> methodWindows
            else -> methodWindows
        }
    }

    private fun requiresDedicatedSearchWindow(type: EmbeddedCode.CodeType): Boolean =
        when (type) {
            EmbeddedCode.CodeType.EXCEPTION_HANDLER,
            EmbeddedCode.CodeType.FINALLY_CLAUSE -> true
            else -> false
        }

    private fun findScopeWindows(
        action: EmbeddedCode,
        generatedFile: File,
        generatedText: String,
    ): List<TextWindow> {
        if (action.type == EmbeddedCode.CodeType.MEMBERS) {
            return findClassBodyWindows(generatedText)
        }
        if (action.type == EmbeddedCode.CodeType.SCOPED_MEMBERS) {
            val generatedRole = detectGeneratedRecognizerRole(generatedFile, generatedText)
            val scopedTarget = action.scopeName?.substringBefore("::")?.lowercase()
            if (scopedTarget == "parser" && generatedRole != "parser") return emptyList()
            if (scopedTarget == "lexer" && generatedRole != "lexer") return emptyList()
            return findClassBodyWindows(generatedText)
        }
        if (action.type == EmbeddedCode.CodeType.ARGUMENT_BLOCK) {
            return when {
                action.contextInfo == "parameters" ||
                    action.contextInfo == "returns" ||
                    action.contextInfo == "locals" -> {
                    findRuleContextBodyWindows(action.scopeName, generatedText)
                }

                action.contextInfo?.startsWith("call to ") == true -> {
                    findParserRuleMethodWindows(action.scopeName, generatedText)
                }

                else -> emptyList()
            }
        }

        val scopeName = action.scopeName ?: return emptyList()
        val actionMethodPattern = methodSignaturePattern("${scopeName}_action")
        val sempredMethodPattern = methodSignaturePattern("${scopeName}_sempred")

        val patterns =
            when (action.type) {
                EmbeddedCode.CodeType.LEXER_ACTION -> listOf(actionMethodPattern)
                EmbeddedCode.CodeType.SEMANTIC_PREDICATE ->
                    listOf(
                        methodSignaturePattern(scopeName),
                        sempredMethodPattern,
                    )
                else -> listOf(methodSignaturePattern(scopeName))
            }

        return findBodyWindows(patterns, generatedText)
    }

    private fun findParserRuleMethodWindows(
        scopeName: String?,
        generatedText: String,
    ): List<TextWindow> {
        val resolvedScopeName = scopeName ?: return emptyList()
        val parserRulePattern = methodSignaturePattern(resolvedScopeName)
        return findBodyWindows(listOf(parserRulePattern), generatedText)
    }

    private fun findRuleContextBodyWindows(
        scopeName: String?,
        generatedText: String,
    ): List<TextWindow> {
        val resolvedScopeName = scopeName ?: return emptyList()
        val contextClassName = generatedRuleContextClassName(resolvedScopeName)
        val contextPattern =
            Regex(
                """(?m)^\s*(?:public\s+)?(?:static\s+)?class\s+${Regex.escape(contextClassName)}\b""",
            )
        val match = contextPattern.find(generatedText) ?: return emptyList()
        return listOfNotNull(findBodyWindowAfter(generatedText, match.range.last + 1))
    }

    private fun generatedRuleContextClassName(scopeName: String): String =
        scopeName.replaceFirstChar { char ->
            if (char.isLowerCase()) {
                char.titlecase()
            } else {
                char.toString()
            }
        } + "Context"

    private fun methodSignaturePattern(methodName: String): Regex {
        val escapedScope = Regex.escape(methodName)
        return Regex(
            "(?m)^\\s*(?:public|private|protected)(?:\\s+[\\w<>,.?\\[\\]]+)*\\s+$escapedScope\\s*\\(",
        )
    }

    private fun findClassBodyWindows(generatedText: String): List<TextWindow> {
        val classMatch = Regex("""\bclass\s+\w+""").find(generatedText) ?: return emptyList()
        return listOfNotNull(findBodyWindowAfter(generatedText, classMatch.range.last + 1))
    }

    private fun findBodyWindows(patterns: List<Regex>, generatedText: String): List<TextWindow> =
        patterns
            .flatMap { pattern ->
                pattern.findAll(generatedText).mapNotNull { match ->
                    findBodyWindowAfter(generatedText, match.range.last + 1)
                }
            }
            .distinct()
            .sortedBy { it.startOffset }

    private fun findBodyWindowAfter(generatedText: String, startOffset: Int): TextWindow? {
        val openingBraceOffset = generatedText.indexOf('{', startOffset)
        if (openingBraceOffset < 0) return null

        val closingBraceOffset = findMatchingClosingBrace(generatedText, openingBraceOffset) ?: return null
        val bodyStartOffset = (openingBraceOffset + 1).coerceAtMost(generatedText.length)
        val bodyEndOffsetExclusive = closingBraceOffset.coerceAtLeast(bodyStartOffset)
        return TextWindow(bodyStartOffset, bodyEndOffsetExclusive)
    }

    private fun detectGeneratedRecognizerRole(generatedFile: File, generatedText: String): String {
        val name = generatedFile.nameWithoutExtension
        if (name.endsWith("Parser")) return "parser"
        if (name.endsWith("Lexer")) return "lexer"
        if (Regex("""\bextends\s+Parser\b""").containsMatchIn(generatedText)) return "parser"
        if (Regex("""\bextends\s+Lexer\b""").containsMatchIn(generatedText)) return "lexer"
        if (Regex("""\)\s*:\s*Parser\b""").containsMatchIn(generatedText)) return "parser"
        if (Regex("""\)\s*:\s*Lexer\b""").containsMatchIn(generatedText)) return "lexer"
        return "unknown"
    }

    private fun extractTryWindow(generatedText: String, methodWindow: TextWindow): TextWindow? =
        extractRuleMethodClauseWindows(generatedText, methodWindow).tryWindow

    private fun extractCatchWindows(
        generatedText: String,
        methodWindow: TextWindow,
    ): List<TextWindow> = extractRuleMethodClauseWindows(generatedText, methodWindow).catchWindows

    private fun extractFinallyWindow(generatedText: String, methodWindow: TextWindow): TextWindow? =
        extractRuleMethodClauseWindows(generatedText, methodWindow).finallyWindow

    private fun extractRuleMethodClauseWindows(
        generatedText: String,
        methodWindow: TextWindow,
    ): ClauseWindows {
        if (methodWindow.endOffsetExclusive <= methodWindow.startOffset) return ClauseWindows.EMPTY

        val bodyText = generatedText.substring(methodWindow.startOffset, methodWindow.endOffsetExclusive)
        val tryMatch = Regex("(?m)^\\s*try\\s*\\{").find(bodyText) ?: return ClauseWindows.EMPTY
        val tryOffset = methodWindow.startOffset + tryMatch.range.first
        val tryOpeningBraceOffset = generatedText.indexOf('{', tryOffset)
        if (tryOpeningBraceOffset < 0 || tryOpeningBraceOffset >= methodWindow.endOffsetExclusive) {
            return ClauseWindows.EMPTY
        }

        val tryClosingBraceOffset =
            findMatchingClosingBrace(generatedText, tryOpeningBraceOffset)?.takeIf {
                it <= methodWindow.endOffsetExclusive
            } ?: return ClauseWindows.EMPTY

        val tryWindow =
            TextWindow(
                startOffset = (tryOpeningBraceOffset + 1).coerceAtMost(generatedText.length),
                endOffsetExclusive = tryClosingBraceOffset.coerceAtLeast(tryOpeningBraceOffset + 1),
            )

        val catchWindows = mutableListOf<TextWindow>()
        var finallyWindow: TextWindow? = null
        var cursor = tryClosingBraceOffset + 1

        while (cursor < methodWindow.endOffsetExclusive) {
            cursor = skipWhitespace(generatedText, cursor, methodWindow.endOffsetExclusive)
            if (cursor >= methodWindow.endOffsetExclusive) break

            when {
                matchesKeywordAt(generatedText, cursor, "catch") -> {
                    val clause = extractCatchClause(generatedText, cursor, methodWindow.endOffsetExclusive) ?: break
                    catchWindows.add(clause.window)
                    cursor = clause.nextCursor
                }

                matchesKeywordAt(generatedText, cursor, "finally") -> {
                    val clause = extractFinallyClause(generatedText, cursor, methodWindow.endOffsetExclusive) ?: break
                    finallyWindow = clause.window
                    cursor = clause.nextCursor
                }

                else -> break
            }
        }

        return ClauseWindows(
            tryWindow = tryWindow,
            catchWindows = catchWindows,
            finallyWindow = finallyWindow,
        )
    }

    private fun findMatchingClosingBrace(text: String, openingBraceOffset: Int): Int? =
        findMatchingDelimiter(text, openingBraceOffset, '{', '}')

    private fun findMatchingDelimiter(
        text: String,
        openingOffset: Int,
        openingChar: Char,
        closingChar: Char,
    ): Int? {
        if (openingOffset !in text.indices || text[openingOffset] != openingChar) return null

        var depth = 0
        for (i in openingOffset until text.length) {
            when (text[i]) {
                openingChar -> depth++
                closingChar -> {
                    depth--
                    if (depth == 0) {
                        return i
                    }
                }
            }
        }

        return null
    }

    private fun skipWhitespace(text: String, startOffset: Int, endOffsetExclusive: Int): Int {
        var cursor = startOffset.coerceAtLeast(0)
        while (cursor < endOffsetExclusive && text[cursor].isWhitespace()) {
            cursor++
        }
        return cursor
    }

    private fun matchesKeywordAt(text: String, offset: Int, keyword: String): Boolean {
        if (offset < 0 || offset + keyword.length > text.length) return false
        if (!text.regionMatches(offset, keyword, 0, keyword.length)) return false

        val afterOffset = offset + keyword.length
        return afterOffset >= text.length || !text[afterOffset].isJavaIdentifierPart()
    }

    private fun extractCatchClause(
        generatedText: String,
        keywordOffset: Int,
        limitExclusive: Int,
    ): ClauseWindowWithCursor? {
        val parenOffset = generatedText.indexOf('(', keywordOffset + "catch".length)
        if (parenOffset !in 0 until limitExclusive) return null

        val closingParenOffset =
            findMatchingDelimiter(generatedText, parenOffset, '(', ')')?.takeIf {
                it < limitExclusive
            } ?: return null

        val openingBraceOffset = generatedText.indexOf('{', closingParenOffset + 1)
        return extractClauseBody(generatedText, openingBraceOffset, limitExclusive)
    }

    private fun extractFinallyClause(
        generatedText: String,
        keywordOffset: Int,
        limitExclusive: Int,
    ): ClauseWindowWithCursor? {
        val openingBraceOffset = generatedText.indexOf('{', keywordOffset + "finally".length)
        return extractClauseBody(generatedText, openingBraceOffset, limitExclusive)
    }

    private fun extractClauseBody(
        generatedText: String,
        openingBraceOffset: Int,
        limitExclusive: Int,
    ): ClauseWindowWithCursor? {
        if (openingBraceOffset !in 0 until limitExclusive) return null

        val closingBraceOffset =
            findMatchingClosingBrace(generatedText, openingBraceOffset)?.takeIf {
                it <= limitExclusive
            } ?: return null

        return ClauseWindowWithCursor(
            window =
                TextWindow(
                    startOffset = (openingBraceOffset + 1).coerceAtMost(generatedText.length),
                    endOffsetExclusive = closingBraceOffset.coerceAtLeast(openingBraceOffset + 1),
                ),
            nextCursor = closingBraceOffset + 1,
        )
    }

    private data class ClauseWindows(
        val tryWindow: TextWindow?,
        val catchWindows: List<TextWindow>,
        val finallyWindow: TextWindow?,
    ) {
        companion object {
            val EMPTY =
                ClauseWindows(
                    tryWindow = null,
                    catchWindows = emptyList(),
                    finallyWindow = null,
                )
        }
    }

    private data class ClauseWindowWithCursor(
        val window: TextWindow,
        val nextCursor: Int,
    )
}
