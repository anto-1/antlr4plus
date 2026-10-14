package com.antlr4plus.dotG4Support.refactor

import com.antlr4plus.parsing.ParsingUtils.nextRealToken
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser

internal sealed interface InlineRuleResult {
    data class Ready(val plan: GrammarEditPlan) : InlineRuleResult

    data class Rejected(val reason: String) : InlineRuleResult
}

/** Inline complete bodies only when their rule scope and every invocation can be removed together. */
internal fun inlineRule(sourceText: String, name: String): InlineRuleResult {
    fun reject(reason: String) = InlineRuleResult.Rejected(reason)
    val source = GrammarSource.parse(sourceText) ?: return reject("Fix grammar syntax errors before inlining a rule.")
    val definition =
        source.rules.singleOrNull { source.nameToken(it).text == name }
            ?: return reject("The rule '$name' must have exactly one definition in this grammar.")
    val definitionTokens = definition.start.tokenIndex..definition.stop.tokenIndex
    val references = RefactorUtils.getAllRuleRefNodes(source.parser, source.tree, name).orEmpty()
    val external = references.filter { it.symbol.tokenIndex !in definitionTokens }
    if (external.isEmpty()) return reject("There are no references to '$name' outside its own definition.")

    if (
        definition is ANTLRv4Parser.ParserRuleSpecContext &&
            (definition.argActionBlock() != null ||
                definition.ruleReturns() != null ||
                definition.localsSpec() != null ||
                definition.throwsSpec() != null ||
                definition.rulePrequel().isNotEmpty() ||
                definition.exceptionGroup().childCount > 0)
    )
        return reject("Inlining '$name' would discard rule declarations, options, or lifecycle code.")
    if (definition is ANTLRv4Parser.LexerRuleContext && definition.optionsSpec() != null)
        return reject("The lexer rule's options cannot be transferred to its callers.")

    val definitionContexts = source.contexts.filter { it.start.tokenIndex in definitionTokens }
    if (
        definitionContexts.any {
            it is ANTLRv4Parser.ActionBlockContext ||
                it is ANTLRv4Parser.ArgActionBlockContext ||
                it is ANTLRv4Parser.ElementOptionsContext ||
                it is ANTLRv4Parser.LabeledElementContext ||
                it is ANTLRv4Parser.LabeledLexerElementContext ||
                it is ANTLRv4Parser.LexerCommandsContext ||
                (it is ANTLRv4Parser.LabeledAltContext && it.POUND() != null)
        }
    )
        return reject(
            "Cannot inline a body containing actions, labels, argument expressions, element options, or lexer commands.",
        )

    val callers = external.mapNotNull { source.containingRule(it) }.toSet()
    for (reference in external) {
        val invocation = reference.parent
        if (invocation is ANTLRv4Parser.SetElementContext)
            return reject("References inside lexer sets cannot be replaced by a rule body.")
        if (
            (invocation is ANTLRv4Parser.RulerefContext &&
                (invocation.argActionBlock() != null || invocation.elementOptions() != null)) ||
                (invocation is ANTLRv4Parser.TerminalContext && invocation.elementOptions() != null)
        )
            return reject("An invocation of '$name' has arguments or options that cannot be transferred to its body.")
        var ancestor = reference.parent
        val caller = source.containingRule(reference)
        while (ancestor != null && ancestor !== caller) {
            if (ancestor is ANTLRv4Parser.LabeledElementContext || ancestor is ANTLRv4Parser.LabeledLexerElementContext)
                return reject("An invocation of '$name' is labeled. Its label requires the original rule or token.")
            ancestor = ancestor.parent
        }
    }
    if (
        definition is ANTLRv4Parser.LexerRuleContext &&
            callers.any {
                it is ANTLRv4Parser.LexerRuleContext && it.optionsSpec() != null
            }
    )
        return reject("A caller has lexer rule options that could change the inlined body's meaning.")

    // Expanding a rule can capture a caller's existing implicit reference, such as $ID.text,
    // by inserting another ID in that caller. Target-language code stays in its original scope.
    val exposedNames =
        definitionContexts
            .mapNotNull {
                when (it) {
                    is ANTLRv4Parser.RulerefContext -> it.RULE_REF().text
                    is ANTLRv4Parser.TerminalContext -> it.TOKEN_REF()?.text
                    else -> null
                }
            }
            .toSet() + name
    for (context in source.contexts) {
        if (context !is ANTLRv4Parser.ActionBlockContext && context !is ANTLRv4Parser.ArgActionBlockContext) continue
        val actionReferences = grammarActionReferences(source.textOf(context))
        if (actionReferences.any { it.nonLocal && it.name == name })
            return reject("An action accesses '$name' through its enclosing rule scope.")
        if (
            source.containingRule(context) in callers &&
                actionReferences.any { !it.nonLocal && it.name in exposedNames }
        )
            return reject(
                "Cannot inline '$name' while caller actions reference its context or names introduced by its body.",
            )
    }

    val colon = definition.getToken(ANTLRv4Lexer.COLON, 0).symbol
    val semi = definition.getToken(ANTLRv4Lexer.SEMI, 0).symbol
    val bodyTokens =
        source.tokens.tokens.subList(colon.tokenIndex + 1, semi.tokenIndex).filter { it.type != ANTLRv4Lexer.WS }
    var body =
        if (bodyTokens.isEmpty()) ""
        else source.text.substring(source.start(bodyTokens.first()), source.end(bodyTokens.last()))
    // A trailing line comment must not consume the caller's suffix or following element.
    if (bodyTokens.lastOrNull()?.type == ANTLRv4Lexer.LINE_COMMENT) body += "\n"
    if (RefactorUtils.ruleHasMultipleOutermostAlts(source.parser, definition)) body = "($body)"
    val edits =
        external.mapTo(mutableListOf()) { reference ->
            val token = reference.symbol
            val suffix = nextRealToken(source.tokens, token.tokenIndex)?.type
            val replacement =
                if (suffix in setOf(ANTLRv4Lexer.STAR, ANTLRv4Lexer.PLUS, ANTLRv4Lexer.QUESTION)) "($body)" else body
            GrammarTextEdit(source.start(token), source.end(token), replacement)
        }
    val recursive = references.any { it.symbol.tokenIndex in definitionTokens }
    val producesTokens = definition is ANTLRv4Parser.LexerRuleContext && definition.FRAGMENT() == null
    if (!recursive && !producesTokens) {
        val stop =
            source.tokens.getHiddenTokensToRight(definition.stop.tokenIndex)?.firstOrNull()?.takeIf {
                it.type == ANTLRv4Lexer.WS
            } ?: definition.stop
        edits.add(GrammarTextEdit(source.start(definition.start), source.end(stop), ""))
    }
    val plan = GrammarEditPlan(sourceText, edits)
    if (GrammarSource.parse(plan.resultText()) == null)
        return reject("The inlined rule would not form valid grammar syntax.")
    return InlineRuleResult.Ready(plan)
}
