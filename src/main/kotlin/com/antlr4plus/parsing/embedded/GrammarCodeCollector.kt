package com.antlr4plus.parsing.embedded

import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.intellij.plugin.parser.ANTLRv4ParserBaseListener
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext

internal class GrammarCodeCollector(
    private val tokenStream: CommonTokenStream,
    private val includeBlankCodeBlocks: Boolean,
) : ANTLRv4ParserBaseListener() {

    val embeddedCode = mutableListOf<EmbeddedCode>()

    private var currentRuleName: String? = null
    private var isInLexerRule = false

    override fun enterAction(ctx: ANTLRv4Parser.ActionContext) {
        val actionBlock = ctx.actionBlock() ?: return
        val actionContent = extractActionContent(actionBlock)
        if (!includeBlankCodeBlocks && actionContent.isBlank()) return

        val scopeName = ctx.actionScopeName()?.text
        val actionName = ctx.identifier()?.text ?: "unknown"

        val type =
            when {
                scopeName != null && actionName == "header" -> EmbeddedCode.CodeType.SCOPED_HEADER
                scopeName != null && actionName == "members" -> EmbeddedCode.CodeType.SCOPED_MEMBERS
                actionName == "header" -> EmbeddedCode.CodeType.HEADER
                actionName == "members" -> EmbeddedCode.CodeType.MEMBERS
                else -> EmbeddedCode.CodeType.UNKNOWN
            }

        val fullScope = if (scopeName != null) "$scopeName::$actionName" else actionName

        addEmbeddedCode(actionBlock, actionContent, type, fullScope)
    }

    override fun enterParserRuleSpec(ctx: ANTLRv4Parser.ParserRuleSpecContext) {
        currentRuleName = ctx.RULE_REF()?.text
        isInLexerRule = false

        val argBlock = ctx.argActionBlock()
        if (argBlock != null) {
            val argContent = extractArgContent(argBlock)
            if (argContent.isNotBlank()) {
                addArgEmbeddedCode(argBlock, argContent, currentRuleName, "parameters")
            }
        }
    }

    override fun exitParserRuleSpec(ctx: ANTLRv4Parser.ParserRuleSpecContext) {
        currentRuleName = null
    }

    override fun enterLexerRule(ctx: ANTLRv4Parser.LexerRuleContext) {
        currentRuleName = ctx.TOKEN_REF()?.text
        isInLexerRule = true
    }

    override fun exitLexerRule(ctx: ANTLRv4Parser.LexerRuleContext) {
        currentRuleName = null
        isInLexerRule = false
    }

    override fun enterRuleAction(ctx: ANTLRv4Parser.RuleActionContext) {
        val actionBlock = ctx.actionBlock() ?: return
        val actionContent = extractActionContent(actionBlock)
        if (!includeBlankCodeBlocks && actionContent.isBlank()) return

        val actionType = ctx.identifier()?.text ?: "unknown"
        val type =
            when (actionType) {
                "init" -> EmbeddedCode.CodeType.RULE_INIT
                "after" -> EmbeddedCode.CodeType.RULE_AFTER
                else -> EmbeddedCode.CodeType.UNKNOWN
            }

        addEmbeddedCode(actionBlock, actionContent, type, currentRuleName, actionType)
    }

    override fun enterActionElement(ctx: ANTLRv4Parser.ActionElementContext) {
        val actionBlock = ctx.actionBlock() ?: return
        val actionContent = extractActionContent(actionBlock)
        if (!includeBlankCodeBlocks && actionContent.isBlank()) return

        val isPredicate = ctx.QUESTION() != null

        val type =
            when {
                isPredicate -> EmbeddedCode.CodeType.SEMANTIC_PREDICATE
                isInLexerRule -> EmbeddedCode.CodeType.LEXER_ACTION
                else -> EmbeddedCode.CodeType.PARSER_ACTION
            }

        addEmbeddedCode(actionBlock, actionContent, type, currentRuleName)
    }

    override fun enterExceptionHandler(ctx: ANTLRv4Parser.ExceptionHandlerContext) {
        val actionBlock = ctx.actionBlock() ?: return
        val actionContent = extractActionContent(actionBlock)
        if (!includeBlankCodeBlocks && actionContent.isBlank()) return

        val argBlock = ctx.argActionBlock()
        val exceptionType = argBlock?.let { extractArgContent(it) }

        addEmbeddedCode(
            actionBlock,
            actionContent,
            EmbeddedCode.CodeType.EXCEPTION_HANDLER,
            currentRuleName,
            exceptionType?.let { "catch($it)" },
        )
    }

    override fun enterFinallyClause(ctx: ANTLRv4Parser.FinallyClauseContext) {
        val actionBlock = ctx.actionBlock() ?: return
        val actionContent = extractActionContent(actionBlock)
        if (!includeBlankCodeBlocks && actionContent.isBlank()) return

        addEmbeddedCode(
            actionBlock,
            actionContent,
            EmbeddedCode.CodeType.FINALLY_CLAUSE,
            currentRuleName,
        )
    }

    override fun enterRuleReturns(ctx: ANTLRv4Parser.RuleReturnsContext) {
        val argBlock = ctx.argActionBlock() ?: return
        val argContent = extractArgContent(argBlock)
        if (argContent.isBlank()) return

        addArgEmbeddedCode(argBlock, argContent, currentRuleName, "returns")
    }

    override fun enterLocalsSpec(ctx: ANTLRv4Parser.LocalsSpecContext) {
        val argBlock = ctx.argActionBlock() ?: return
        val argContent = extractArgContent(argBlock)
        if (argContent.isBlank()) return

        addArgEmbeddedCode(argBlock, argContent, currentRuleName, "locals")
    }

    override fun enterRuleref(ctx: ANTLRv4Parser.RulerefContext) {
        val argBlock = ctx.argActionBlock() ?: return
        val argContent = extractArgContent(argBlock)
        if (argContent.isBlank()) return

        val refName = ctx.RULE_REF()?.text
        addArgEmbeddedCode(argBlock, argContent, currentRuleName, "call to $refName")
    }

    private fun extractActionContent(actionBlock: ANTLRv4Parser.ActionBlockContext): String =
        extractBlockContent(actionBlock, "{", "}")

    private fun extractArgContent(argBlock: ANTLRv4Parser.ArgActionBlockContext): String =
        extractBlockContent(argBlock, "[", "]")

    private fun extractBlockContent(
        block: ParserRuleContext,
        prefix: String,
        suffix: String,
    ): String {
        val startToken = block.start
        val stopToken = block.stop

        if (startToken == null || stopToken == null) return ""

        // Preserve whitespace for exact source mapping and edit synchronization.
        return tokenStream.getText(startToken, stopToken).removeSurrounding(prefix, suffix)
    }

    private fun addEmbeddedCode(
        block: ParserRuleContext,
        content: String,
        type: EmbeddedCode.CodeType,
        scopeName: String?,
        contextInfo: String? = null,
    ) {
        val startToken = block.start ?: return
        val stopToken = block.stop ?: return

        // Body indices exclude the opening and closing delimiters.
        val bodyStartOffset = startToken.startIndex + 1
        val bodyEndOffsetExclusive = stopToken.startIndex

        embeddedCode.add(
            EmbeddedCode(
                type = type,
                content = content,
                startLine = startToken.line,
                startColumn = startToken.charPositionInLine,
                endLine = stopToken.line,
                endColumn = stopToken.charPositionInLine + (stopToken.text?.length ?: 0),
                scopeName = scopeName,
                contextInfo = contextInfo,
                bodyStartOffset = bodyStartOffset,
                bodyEndOffsetExclusive = bodyEndOffsetExclusive,
            ),
        )
    }

    private fun addArgEmbeddedCode(
        argBlock: ANTLRv4Parser.ArgActionBlockContext,
        content: String,
        scopeName: String?,
        contextInfo: String?,
    ) =
        addEmbeddedCode(
            argBlock,
            content,
            EmbeddedCode.CodeType.ARGUMENT_BLOCK,
            scopeName,
            contextInfo,
        )
}
