package com.antlr4plus.dotG4Support.completion

import com.antlr4plus.codeGen.GenAntlrCode
import com.antlr4plus.dotG4Support.psi.GrammarSpecNode
import com.antlr4plus.dotG4Support.psi.OptionsSpecNode
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/** Offers configured option names and enumerated values for the containing grammar type. */
class OptionsCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        resultSet: CompletionResultSet,
    ) {
        val position = parameters.position

        PsiTreeUtil.getParentOfType(position, OptionsSpecNode::class.java) ?: return

        val grammarType = detectGrammarType(position)

        if (isOnValueSide(position)) {
            val optionName = inferEnclosingOptionName(position) ?: return
            val values = ENUMERATED_OPTION_VALUES[optionName] ?: return
            val cs = resultSet.caseInsensitive()
            values.forEach { value ->
                cs.addElement(LookupElementBuilder.create(value))
            }
            return
        }

        val candidates =
            when (grammarType) {
                GrammarType.LEXER -> LEXER_OPTIONS
                GrammarType.PARSER -> PARSER_OPTIONS
                GrammarType.COMBINED -> COMBINED_OPTIONS
            }

        val cs = resultSet.caseInsensitive()
        candidates.forEach { (name, hint) ->
            cs.addElement(
                LookupElementBuilder.create(name).withTypeText(hint, true),
            )
        }
    }

    private enum class GrammarType {
        LEXER,
        PARSER,
        COMBINED,
    }

    private fun detectGrammarType(position: com.intellij.psi.PsiElement): GrammarType {
        val grammarSpec =
            PsiTreeUtil.getParentOfType(position, GrammarSpecNode::class.java) ?: return GrammarType.COMBINED
        val text = grammarSpec.text.take(64).trimStart()
        return when {
            text.startsWith("lexer") -> GrammarType.LEXER
            text.startsWith("parser") -> GrammarType.PARSER
            else -> GrammarType.COMBINED
        }
    }

    /** Scans up to 32 preceding PSI nodes for an equals sign before a semicolon or opening brace. */
    private fun isOnValueSide(position: com.intellij.psi.PsiElement): Boolean {
        var node: com.intellij.psi.PsiElement? = position.prevSibling ?: position.parent?.prevSibling
        var safety = 0
        while (node != null && safety < 32) {
            val t = node.text
            for (i in t.indices.reversed()) {
                when (t[i]) {
                    '=' -> return true
                    ';',
                    '{' -> return false
                }
            }
            node = node.prevSibling ?: node.parent?.prevSibling
            safety++
        }
        return false
    }

    /** Reads the identifier preceding the most recent equals sign in the options block. */
    private fun inferEnclosingOptionName(position: com.intellij.psi.PsiElement): String? {
        val optionsSpec = PsiTreeUtil.getParentOfType(position, OptionsSpecNode::class.java) ?: return null
        val blockText = optionsSpec.text
        val caretInBlock = position.textRange.startOffset - optionsSpec.textRange.startOffset
        if (caretInBlock <= 0 || caretInBlock > blockText.length) return null

        val eqIdx = blockText.lastIndexOf('=', startIndex = caretInBlock.coerceAtMost(blockText.length - 1))
        if (eqIdx < 0) return null

        var end = eqIdx - 1
        while (end >= 0 && blockText[end].isWhitespace()) end--
        if (end < 0) return null

        var start = end
        while (start >= 0 && (blockText[start].isLetterOrDigit() || blockText[start] == '_')) start--
        return blockText.substring(start + 1, end + 1).takeIf { it.isNotEmpty() }
    }

    companion object {

        private val LEXER_OPTIONS: List<Pair<String, String>> =
            listOf(
                "superClass" to "Base class for the generated lexer",
                "TokenLabelType" to "Base type for token labels in actions",
                "tokenVocab" to "Reuse tokens from another grammar/lexer",
                "language" to "Target language (Java, Kotlin, ...)",
                "caseInsensitive" to "Match input case-insensitively (true | false)",
                "exportMacro" to "C++ DLL export macro for generated headers",
            )

        private val PARSER_OPTIONS: List<Pair<String, String>> =
            listOf(
                "superClass" to "Base class for the generated parser",
                "contextSuperClass" to "Base class for ParserRuleContext subclasses",
                "TokenLabelType" to "Base type for token labels in actions",
                "tokenVocab" to "Reuse tokens from a lexer/another grammar",
                "language" to "Target language (Java, Kotlin, ...)",
                "exportMacro" to "C++ DLL export macro for generated headers",
            )

        private val COMBINED_OPTIONS: List<Pair<String, String>> =
            listOf(
                "superClass" to "Base class for the generated parser",
                "contextSuperClass" to "Base class for ParserRuleContext subclasses",
                "TokenLabelType" to "Base type for token labels in actions",
                "tokenVocab" to "Reuse tokens from another grammar/lexer",
                "language" to "Target language (Java, Kotlin, ...)",
                "caseInsensitive" to "Match input case-insensitively (true | false)",
                "exportMacro" to "C++ DLL export macro for generated headers",
            )

        private val ENUMERATED_OPTION_VALUES: Map<String, List<String>> =
            mapOf(
                "language" to GenAntlrCode.SUPPORTED_TARGET_LANGUAGES,
                "caseInsensitive" to listOf("true", "false"),
            )
    }
}
