package com.antlr4plus.dotG4Support.formatting

import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.v4.runtime.*

/** Token-based grammar formatter using a hanging layout for expanded rules. */
class ANTLRv4DirectFormatter(
    private val settings: ANTLRv4FormattingSettings,
) {
    private val indentStr = if (settings.useTab) "\t" else " ".repeat(settings.indentWidth)

    fun format(text: String): String = formatHanging(text)

    private fun findNextNonWhitespace(tokens: List<Token>, startIndex: Int): Token? {
        for (i in startIndex until tokens.size) {
            val token = tokens[i]
            if (
                token.type != ANTLRv4Lexer.WS &&
                    token.type != ANTLRv4Lexer.LINE_COMMENT &&
                    token.type != ANTLRv4Lexer.BLOCK_COMMENT &&
                    token.type != ANTLRv4Lexer.DOC_COMMENT &&
                    token.type != Token.EOF
            ) {
                return token
            }
        }
        return null
    }

    private fun isTopLevelOr(tokens: List<Token>, orIndex: Int): Boolean {
        var parenDepth = 0
        var foundColon = false

        for (i in (orIndex - 1) downTo 0) {
            val token = tokens[i]
            when (token.type) {
                ANTLRv4Lexer.RPAREN -> parenDepth++
                ANTLRv4Lexer.LPAREN -> parenDepth--
                ANTLRv4Lexer.COLON -> {
                    foundColon = true
                    break
                }
                ANTLRv4Lexer.SEMI -> break
            }
        }

        return foundColon && parenDepth == 0
    }

    private fun isRuleEndingSemi(tokens: List<Token>, semiIndex: Int): Boolean {
        var braceDepth = 0
        var parenDepth = 0
        var foundColon = false

        for (i in (semiIndex - 1) downTo 0) {
            val token = tokens[i]
            when (token.type) {
                ANTLRv4Lexer.RBRACE -> braceDepth++
                ANTLRv4Lexer.LBRACE -> braceDepth--
                ANTLRv4Lexer.RPAREN -> parenDepth++
                ANTLRv4Lexer.LPAREN -> parenDepth--
                ANTLRv4Lexer.COLON -> {
                    if (braceDepth <= 0 && parenDepth <= 0) {
                        foundColon = true
                        break
                    }
                }
                ANTLRv4Lexer.SEMI -> break
            }
        }

        return foundColon && braceDepth == 0 && parenDepth == 0
    }

    private fun formatHanging(text: String): String {
        val lexer = ANTLRv4Lexer(CharStreams.fromString(text))
        val tokens = CommonTokenStream(lexer)
        tokens.fill()

        val allTokens = tokens.tokens
        if (allTokens.isEmpty()) return text

        val output = StringBuilder()
        var i = 0
        var inRule = false
        var lastNonWsTokenType = -1
        var skipNextWhitespace = false
        var shouldExpandCurrentRule = true
        var lastRuleWasOneLiner = false
        var lastWasLineComment = false
        var hadBlankLinesBefore = false

        while (i < allTokens.size) {
            val token = allTokens[i]

            when (token.type) {
                Token.EOF -> {
                    i++
                    continue
                }

                ANTLRv4Lexer.RULE_REF,
                ANTLRv4Lexer.TOKEN_REF -> {
                    val nextNonWs = findNextNonWhitespace(allTokens, i + 1)
                    val isRuleDef =
                        nextNonWs != null &&
                            (nextNonWs.type == ANTLRv4Lexer.COLON ||
                                nextNonWs.type == ANTLRv4Lexer.OPTIONS ||
                                nextNonWs.type == ANTLRv4Lexer.RETURNS ||
                                nextNonWs.type == ANTLRv4Lexer.LOCALS ||
                                nextNonWs.type == ANTLRv4Lexer.THROWS ||
                                (lastNonWsTokenType == ANTLRv4Lexer.FRAGMENT))

                    if (isRuleDef && !inRule) {
                        inRule = true
                        shouldExpandCurrentRule = shouldExpandRuleHanging(allTokens, i)

                        trimTrailingWhitespaceAndNewlines(output)
                        if (output.isNotEmpty()) {
                            if (lastWasLineComment) {
                                output.append("\n")
                            } else if (lastRuleWasOneLiner && !shouldExpandCurrentRule && !hadBlankLinesBefore) {
                                output.append("\n")
                            } else {
                                output.append("\n\n")
                            }
                        }
                        lastWasLineComment = false
                        hadBlankLinesBefore = false
                        output.append(token.text)
                    } else {
                        output.append(token.text)
                    }
                    lastNonWsTokenType = token.type
                }

                ANTLRv4Lexer.FRAGMENT -> {
                    val nextNonWs = findNextNonWhitespace(allTokens, i + 1)
                    if (nextNonWs?.type == ANTLRv4Lexer.TOKEN_REF) {
                        inRule = true
                        shouldExpandCurrentRule = shouldExpandRuleHanging(allTokens, i)

                        trimTrailingWhitespaceAndNewlines(output)
                        if (output.isNotEmpty()) {
                            if (lastWasLineComment) {
                                output.append("\n")
                            } else if (lastRuleWasOneLiner && !shouldExpandCurrentRule && !hadBlankLinesBefore) {
                                output.append("\n")
                            } else {
                                output.append("\n\n")
                            }
                        }
                        lastWasLineComment = false
                        hadBlankLinesBefore = false
                        output.append(token.text)
                    } else {
                        output.append(token.text)
                    }
                    lastNonWsTokenType = token.type
                }

                ANTLRv4Lexer.COLON -> {
                    if (inRule && shouldExpandCurrentRule && settings.alignColons == ColonAlignmentMode.HANGING) {
                        trimTrailingWhitespaceAndNewlines(output)
                        if (output.isNotEmpty()) {
                            output.append("\n")
                        }
                        output.append(indentStr)
                        output.append(": ")
                        skipNextWhitespace = true
                    } else if (inRule && !shouldExpandCurrentRule) {
                        trimTrailingWhitespace(output)
                        output.append(": ")
                        skipNextWhitespace = true
                    } else {
                        output.append(token.text)
                    }
                    lastNonWsTokenType = token.type
                }

                ANTLRv4Lexer.OR -> {
                    if (inRule && shouldExpandCurrentRule && settings.alignColons == ColonAlignmentMode.HANGING) {
                        if (isTopLevelOr(allTokens, i)) {
                            trimTrailingWhitespaceAndNewlines(output)
                            if (output.isNotEmpty()) {
                                output.append("\n")
                            }
                            output.append(indentStr)
                            output.append("| ")
                            skipNextWhitespace = true
                        } else {
                            output.append(token.text)
                        }
                    } else {
                        output.append(token.text)
                    }
                    lastNonWsTokenType = token.type
                }

                ANTLRv4Lexer.SEMI -> {
                    val isRuleEnding = isRuleEndingSemi(allTokens, i)
                    if (
                        inRule &&
                            isRuleEnding &&
                            shouldExpandCurrentRule &&
                            (settings.alignSemicolons == SemicolonAlignmentMode.HANGING ||
                                settings.alignSemicolons == SemicolonAlignmentMode.OWN_LINE)
                    ) {
                        trimTrailingWhitespaceAndNewlines(output)
                        if (output.isNotEmpty()) {
                            output.append("\n")
                        }
                        output.append(indentStr)
                        output.append(";")
                        lastRuleWasOneLiner = false
                        inRule = false
                        shouldExpandCurrentRule = true
                    } else if (inRule && isRuleEnding && !shouldExpandCurrentRule) {
                        trimTrailingWhitespace(output)
                        output.append(";")
                        lastRuleWasOneLiner = true
                        inRule = false
                        shouldExpandCurrentRule = true
                    } else {
                        output.append(token.text)
                        if (inRule && isRuleEnding) {
                            lastRuleWasOneLiner = false
                            inRule = false
                            shouldExpandCurrentRule = true
                        }
                    }
                    lastNonWsTokenType = token.type
                }

                ANTLRv4Lexer.WS -> {
                    if (!inRule && token.text.count { it == '\n' } >= 2) {
                        hadBlankLinesBefore = true
                    }

                    if (skipNextWhitespace) {
                        skipNextWhitespace = false
                        if (shouldExpandCurrentRule) {
                            val newlineIdx = token.text.indexOf('\n')
                            if (newlineIdx >= 0) {
                                output.append(token.text.substring(newlineIdx))
                            }
                        }
                    } else {
                        val nextToken = if (i + 1 < allTokens.size) allTokens[i + 1] else null
                        val nextIsStructural =
                            nextToken != null &&
                                inRule &&
                                (nextToken.type == ANTLRv4Lexer.COLON ||
                                    (nextToken.type == ANTLRv4Lexer.OR && isTopLevelOr(allTokens, i + 1)) ||
                                    (nextToken.type == ANTLRv4Lexer.SEMI && isRuleEndingSemi(allTokens, i + 1)))

                        if (!nextIsStructural) {
                            output.append(token.text)
                        }
                    }
                }

                ANTLRv4Lexer.LINE_COMMENT -> {
                    output.append(token.text)
                    lastNonWsTokenType = token.type
                    skipNextWhitespace = false
                    if (!inRule) {
                        lastWasLineComment = true
                    }
                }

                ANTLRv4Lexer.BLOCK_COMMENT,
                ANTLRv4Lexer.DOC_COMMENT -> {
                    output.append(token.text)
                    lastNonWsTokenType = token.type
                    skipNextWhitespace = false
                }

                else -> {
                    output.append(token.text)
                    if (token.type != ANTLRv4Lexer.WS) {
                        lastNonWsTokenType = token.type
                    }
                    skipNextWhitespace = false
                }
            }
            i++
        }

        return output.toString()
    }

    private fun shouldExpandRuleHanging(tokens: List<Token>, ruleStartIndex: Int): Boolean =
        !settings.inlineSingleAlternativeRules || !isSingleTokenRule(tokens, ruleStartIndex)

    private fun isSingleTokenRule(tokens: List<Token>, ruleStartIndex: Int): Boolean {
        var foundColon = false
        var tokenCount = 0

        for (i in ruleStartIndex until tokens.size) {
            val token = tokens[i]

            when (token.type) {
                ANTLRv4Lexer.COLON -> foundColon = true
                ANTLRv4Lexer.RARROW -> {
                    if (foundColon && tokenCount == 1) {
                        return true
                    }
                    return false
                }
                ANTLRv4Lexer.OR -> {
                    if (foundColon && isTopLevelOr(tokens, i)) {
                        return false
                    }
                    if (foundColon) tokenCount++
                }
                ANTLRv4Lexer.SEMI -> {
                    if (isRuleEndingSemi(tokens, i)) {
                        return tokenCount == 1
                    }
                    if (foundColon) tokenCount++
                }
                ANTLRv4Lexer.WS,
                ANTLRv4Lexer.LINE_COMMENT,
                ANTLRv4Lexer.BLOCK_COMMENT,
                ANTLRv4Lexer.DOC_COMMENT -> {}
                Token.EOF -> return false
                else -> {
                    if (foundColon) tokenCount++
                }
            }

            if (tokenCount > 1) return false
        }
        return false
    }

    private fun trimTrailingWhitespace(sb: StringBuilder) {
        while (sb.isNotEmpty() && sb.last().isWhitespace() && sb.last() != '\n') {
            sb.deleteCharAt(sb.length - 1)
        }
    }

    private fun trimTrailingWhitespaceAndNewlines(sb: StringBuilder) {
        while (sb.isNotEmpty() && sb.last().isWhitespace()) {
            sb.deleteCharAt(sb.length - 1)
        }
    }
}
