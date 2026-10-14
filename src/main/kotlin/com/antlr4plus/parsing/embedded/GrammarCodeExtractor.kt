package com.antlr4plus.parsing.embedded

import com.intellij.openapi.diagnostic.Logger
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeWalker

/** Extracts code blocks and class-valued options with the ANTLRv4 grammar parser. */
object GrammarCodeExtractor {
    private val LOG = Logger.getInstance(GrammarCodeExtractor::class.java)

    /** A superClass, contextSuperClass or TokenLabelType dependency. */
    data class SuperClassRef(
        val className: String,
        val type: String, // Declaring grammar: parser or lexer.
        val option: String = "superClass",
    )

    private val CLASS_VALUED_OPTIONS =
        setOf(
            "superClass",
            "contextSuperClass",
            "TokenLabelType",
        )

    /** Extracts blocks from one text snapshot. */
    fun extract(
        content: String,
        grammarPath: String,
        includeBlankCodeBlocks: Boolean = false,
    ): List<EmbeddedCode> {
        val charStream = CharStreams.fromString(content, grammarPath)
        val lexer = ANTLRv4Lexer(charStream)
        val tokenStream = CommonTokenStream(lexer)
        val parser = ANTLRv4Parser(tokenStream)

        lexer.removeErrorListeners()
        parser.removeErrorListeners()

        val tree = parser.grammarSpec()
        val extractor = GrammarCodeCollector(tokenStream, includeBlankCodeBlocks)
        ParseTreeWalker.DEFAULT.walk(extractor, tree)
        return extractor.embeddedCode
    }

    private fun extractGrammarType(tree: ANTLRv4Parser.GrammarSpecContext): String {
        val grammarType = tree.grammarType() ?: return "combined"
        return when {
            grammarType.LEXER() != null -> "lexer"
            grammarType.PARSER() != null -> "parser"
            else -> "combined"
        }
    }

    /** Reads class-valued prequel options from the parse tree. */
    fun extractSuperClassOptions(content: String): List<SuperClassRef> {
        val results = mutableListOf<SuperClassRef>()

        try {
            val charStream = CharStreams.fromString(content)
            val lexer = ANTLRv4Lexer(charStream)
            val tokenStream = CommonTokenStream(lexer)
            val parser = ANTLRv4Parser(tokenStream)

            lexer.removeErrorListeners()
            parser.removeErrorListeners()

            val tree = parser.grammarSpec()

            val grammarType = extractGrammarType(tree)

            for (prequel in tree.prequelConstruct()) {
                val optionsSpec = prequel.optionsSpec() ?: continue

                for (option in optionsSpec.option()) {
                    val optionName = option.identifier()?.text ?: continue
                    if (optionName !in CLASS_VALUED_OPTIONS) continue

                    val optionValue = option.optionValue() ?: continue
                    val className = extractOptionValueAsString(optionValue)

                    if (className.isNotBlank()) {
                        // Combined grammars apply this option to the parser.
                        val type = if (grammarType == "lexer") "lexer" else "parser"
                        results.add(SuperClassRef(className, type, optionName))
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Error parsing grammar for superClass extraction: ${e.message}")
        }

        return results
    }

    private fun extractOptionValueAsString(optionValue: ANTLRv4Parser.OptionValueContext): String {
        val stringLiteral = optionValue.STRING_LITERAL()
        if (stringLiteral != null) {
            val text = stringLiteral.text
            return text.removeSurrounding("'").removeSurrounding("\"")
        }

        val identifiers = optionValue.identifier()
        if (identifiers.isNotEmpty()) {
            return identifiers.joinToString(".") { it.text }
        }

        return ""
    }
}
