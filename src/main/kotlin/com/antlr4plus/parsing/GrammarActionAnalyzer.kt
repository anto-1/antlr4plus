package com.antlr4plus.parsing

import com.antlr4plus.parsing.embedded.GrammarCodeExtractor
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.charset.Charset

/** Heuristically discovers Java/Kotlin source dependencies in grammar actions and support files. */
object GrammarActionAnalyzer {
    private val LOG = Logger.getInstance(GrammarActionAnalyzer::class.java)

    private val CLASS_REFERENCE_PATTERNS =
        listOf(
            // Static members and qualified constructor calls.
            Regex("""\b([A-Z][a-zA-Z0-9_]*)\s*\.\s*[a-zA-Z_][a-zA-Z0-9_]*"""),
            // Declarations, generics, casts and Java/Kotlin constructors.
            Regex("""\b([A-Z][a-zA-Z0-9_]*)\s*(?:<|\s+\w|\)|\()"""),
            Regex(
                """\bimport\s+(?:static\s+)?(?:[a-zA-Z_][a-zA-Z0-9_]*\.)*([A-Z][a-zA-Z0-9_]*)\b""",
            ),
            Regex("""(?:extends|implements)\s+([A-Z][a-zA-Z0-9_]*)"""),
            // Kotlin parameter, return and superclass types.
            Regex(""":\s*([A-Z][a-zA-Z0-9_]*)\b"""),
        )
    private val KOTLIN_SUPERTYPES =
        Regex(
            """:\s*[A-Z][a-zA-Z0-9_]*(?:\([^)]*\))?(?:\s*,\s*([A-Z][a-zA-Z0-9_]*))+""",
        )
    private val ADDITIONAL_SUPERTYPE = Regex(""",\s*([A-Z][a-zA-Z0-9_]*)""")

    private val STANDARD_CLASSES =
        setOf(
            "System",
            "String",
            "Integer",
            "Long",
            "Double",
            "Float",
            "Boolean",
            "Character",
            "Byte",
            "Short",
            "Object",
            "Class",
            "Math",
            "Arrays",
            "List",
            "ArrayList",
            "HashMap",
            "HashSet",
            "Map",
            "Set",
            "Collection",
            "StringBuilder",
            "StringBuffer",
            "Exception",
            "RuntimeException",
            "Thread",
            "Runnable",
            "Comparable",
            "Iterable",
            "Iterator",
            "InputStream",
            "OutputStream",
            "Reader",
            "Writer",
            "File",
            "Pattern",
            "Matcher",
            "Scanner",
            "Random",
            "Date",
            "Calendar",
            "BigInteger",
            "BigDecimal",
            "Optional",
            "Stream",
            "Collectors",
            "Any",
            "Unit",
            "Nothing",
            "Int",
            "Char",
            "Number",
            "Array",
        )

    private val ANTLR_CLASSES =
        setOf(
            "Token",
            "TokenStream",
            "CommonTokenStream",
            "CharStream",
            "CharStreams",
            "Lexer",
            "Parser",
            "ParserRuleContext",
            "RuleContext",
            "ParseTree",
            "TerminalNode",
            "ErrorNode",
            "RecognitionException",
            "ANTLRInputStream",
            "ANTLRFileStream",
            "BufferedTokenStream",
            "Vocabulary",
            "ATN",
            "PredictionMode",
            "BailErrorStrategy",
            "DefaultErrorStrategy",
        )

    data class AnalysisResult(
        val referencedClasses: Set<String>,
        val sourceGrammarName: String = "",
    )

    fun analyzeGrammarFile(
        grammarFile: File,
        charset: Charset = Charsets.UTF_8,
    ): AnalysisResult {
        if (!grammarFile.exists()) {
            LOG.warn("Grammar file does not exist: ${grammarFile.absolutePath}")
            return AnalysisResult(emptySet(), grammarFile.name)
        }
        return analyzeGrammarContent(grammarFile.readText(charset)).copy(sourceGrammarName = grammarFile.name)
    }

    fun analyzeGrammarContent(content: String): AnalysisResult {
        val embeddedCode = GrammarCodeExtractor.extract(content, "<grammar>")
        val referencedClasses =
            embeddedCode.flatMapTo(mutableSetOf()) {
                extractClassReferences(it.content)
            }
        LOG.info(
            "Grammar analysis found ${referencedClasses.size} external class references: $referencedClasses",
        )
        return AnalysisResult(referencedClasses)
    }

    private fun extractClassReferences(source: String): Set<String> {
        val content = stripCommentsAndStrings(source)
        val classes = mutableSetOf<String>()
        fun collect(pattern: Regex, text: String) {
            for (match in pattern.findAll(text)) {
                val className = match.groupValues[1]
                if (isExternalClass(className)) classes.add(className)
            }
        }
        CLASS_REFERENCE_PATTERNS.forEach { collect(it, content) }
        KOTLIN_SUPERTYPES.findAll(content).forEach { collect(ADDITIONAL_SUPERTYPE, it.value) }
        return classes
    }

    private fun isExternalClass(className: String): Boolean {
        if (className in STANDARD_CLASSES || className in ANTLR_CLASSES) return false

        // Treat uppercase-only names as likely constants.
        if (className.all { it.isUpperCase() || it == '_' }) return false

        // Short names are usually variables or type parameters.
        if (className.length <= 2) return false

        return true
    }

    fun analyzeSourceFile(sourceFile: File): Set<String> {
        if (!sourceFile.exists()) {
            LOG.warn("Source file does not exist: ${sourceFile.absolutePath}")
            return emptySet()
        }
        val classes = extractClassReferences(sourceFile.readText())
        LOG.info(
            "Source file ${sourceFile.name} analysis found ${classes.size} external class references: $classes",
        )
        return classes
    }

    /**
     * Masks comments and literal contents while preserving offsets and line breaks. Handles Java/Kotlin quote forms,
     * but does not track nested Kotlin block comments.
     */
    internal fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else '\u0000'

            when {
                c == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < n) {
                        val ch = source[i]
                        if (ch == '*' && i + 1 < n && source[i + 1] == '/') {
                            out.append("  ")
                            i += 2
                            break
                        }
                        out.append(if (ch == '\n' || ch == '\r') ch else ' ')
                        i++
                    }
                }

                c == '/' && next == '/' -> {
                    out.append("  ")
                    i += 2
                    while (i < n && source[i] != '\n' && source[i] != '\r') {
                        out.append(' ')
                        i++
                    }
                }

                c == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"' -> {
                    out.append("   ")
                    i += 3
                    while (i + 2 < n && !(source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"')) {
                        val ch = source[i]
                        out.append(if (ch == '\n' || ch == '\r') ch else ' ')
                        i++
                    }
                    if (i + 2 < n) {
                        out.append("   ")
                        i += 3
                    }
                }

                c == '"' || c == '\'' -> {
                    out.append(c)
                    i++
                    while (i < n) {
                        val ch = source[i]
                        when {
                            ch == '\\' && i + 1 < n -> {
                                out.append("  ")
                                i += 2
                            }
                            ch == c -> {
                                out.append(c)
                                i++
                                break
                            }
                            ch == '\n' || ch == '\r' -> {
                                out.append(ch)
                                i++
                                break
                            }
                            else -> {
                                out.append(' ')
                                i++
                            }
                        }
                    }
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
