package com.antlr4plus.parsing.embedded

/** Target-language code extracted from a grammar, with source location and scope. */
data class EmbeddedCode(
    val type: CodeType,
    /** Body text without surrounding braces or brackets. */
    val content: String,
    /** One-based start line. */
    val startLine: Int,
    /** Zero-based start column. */
    val startColumn: Int,
    /** One-based end line. */
    val endLine: Int,
    /** Zero-based end column. */
    val endColumn: Int,
    /** Rule name or named-action scope, when present. */
    val scopeName: String?,
    /** Action name or argument context, such as init, returns or locals. */
    val contextInfo: String? = null,

    /** ANTLR input index after the opening delimiter, or -1 when unavailable. */
    val bodyStartOffset: Int = -1,

    /** Exclusive ANTLR input index at the closing delimiter, or -1 when unavailable. */
    val bodyEndOffsetExclusive: Int = -1,
) {
    enum class CodeType(val displayName: String) {
        HEADER("Header"),
        MEMBERS("Members"),

        /** @parser::header or @lexer::header. */
        SCOPED_HEADER("Scoped Header"),

        /** @parser::members or @lexer::members. */
        SCOPED_MEMBERS("Scoped Members"),
        RULE_INIT("Rule Init"),
        RULE_AFTER("Rule After"),
        PARSER_ACTION("Parser Action"),
        LEXER_ACTION("Lexer Action"),

        /** { ... }? */
        SEMANTIC_PREDICATE("Semantic Predicate"),

        /** [ ... ] parameters, arguments, returns or locals. */
        ARGUMENT_BLOCK("Arguments"),
        EXCEPTION_HANDLER("Exception Handler"),
        FINALLY_CLAUSE("Finally Clause"),
        UNKNOWN("Unknown"),
    }

    fun contextDescription(): String = buildString {
        append(type.displayName)
        if (scopeName != null) {
            append(" in ")
            append(scopeName)
        }
        if (contextInfo != null) {
            append(" ($contextInfo)")
        }
    }
}
