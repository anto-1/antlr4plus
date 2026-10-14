package com.antlr4plus.dotG4Support.refactor

import com.antlr4plus.util.rethrowFatalOrCancellation
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.v4.parse.ScopeParser

internal sealed interface DuplicateRuleResult {
    data class Ready(val plan: GrammarEditPlan) : DuplicateRuleResult

    data class Rejected(val reason: String) : DuplicateRuleResult
}

internal fun duplicateRule(sourceText: String, name: String): DuplicateRuleResult {
    fun reject(reason: String) = DuplicateRuleResult.Rejected(reason)
    val source =
        GrammarSource.parse(sourceText) ?: return reject("Fix grammar syntax errors before duplicating a rule.")
    val definition = source.definition(name) ?: return reject("The rule '$name' is not defined in this grammar.")
    val definitionTokens = definition.start.tokenIndex..definition.stop.tokenIndex
    val references = RefactorUtils.getAllRuleRefNodes(source.parser, source.tree, name).orEmpty()
    val external = references.filter { it.symbol.tokenIndex !in definitionTokens }
    if (external.size < 2) return reject("Select a rule with at least two references outside its own definition.")
    val producesTokens = definition is ANTLRv4Parser.LexerRuleContext && definition.FRAGMENT() == null
    if (
        producesTokens &&
            source.contexts.any { it is ANTLRv4Parser.LexerCommandsContext && it.start.tokenIndex in definitionTokens }
    ) {
        return reject("Lexer rules with commands cannot be copied into fragment rules.")
    }
    val actions =
        source.contexts.filter { it is ANTLRv4Parser.ActionBlockContext || it is ANTLRv4Parser.ArgActionBlockContext }
    val actionReferences = actions.associateWith { grammarActionReferences(source.textOf(it)) }
    if (
        actions.any {
            it.start.tokenIndex !in definitionTokens && actionReferences.getValue(it).any { ref -> ref.name == name }
        }
    ) {
        return reject(
            "Actions refer to '$name' by name. Give those invocations explicit labels before making separate copies.",
        )
    }
    val attributeNames =
        try {
            if (definition is ANTLRv4Parser.ParserRuleSpecContext) {
                listOfNotNull(
                        definition.argActionBlock(),
                        definition.ruleReturns()?.argActionBlock(),
                        definition.localsSpec()?.argActionBlock(),
                    )
                    .flatMap {
                        ScopeParser.parseTypedArgList(null, source.textOf(it).removeSurrounding("[", "]"), null)
                            .attributes
                            .keys
                    }
                    .toSet()
            } else emptySet()
        } catch (failure: RuntimeException) {
            rethrowFatalOrCancellation(failure)
            return reject("The rule's argument, return, or local declarations could not be resolved.")
        }

    val occupied = source.occupiedNames.toMutableSet()
    var suffix = 1
    fun nextName(base: String): String {
        var candidate = "$base${suffix++}"
        while (!occupied.add(candidate)) candidate = "$base${suffix++}"
        return candidate
    }
    val names = external.map { nextName(name) }
    val definitionStart = source.start(definition.start)
    val definitionText = source.textOf(definition)
    val internalReferences = references.filter { it.symbol.tokenIndex in definitionTokens }
    val definitionActions = actions.filter { it.start.tokenIndex in definitionTokens }
    val labeledAlternatives =
        source.contexts.filterIsInstance<ANTLRv4Parser.LabeledAltContext>().filter {
            it.start.tokenIndex in definitionTokens && it.POUND() != null
        }
    val copies = names.map { newName ->
        val changes = mutableListOf<GrammarTextEdit>()
        fun replace(start: Int, end: Int, replacement: String) {
            changes.add(GrammarTextEdit(start - definitionStart, end - definitionStart, replacement))
        }
        val nameToken = source.nameToken(definition)
        replace(source.start(nameToken), source.end(nameToken), (if (producesTokens) "fragment " else "") + newName)
        for (reference in internalReferences) {
            replace(source.start(reference.symbol), source.end(reference.symbol), newName)
        }
        for (action in definitionActions) {
            val actionStart = source.start(action.start)
            for (reference in actionReferences.getValue(action)) {
                if (reference.name == name && (reference.nonLocal || name !in attributeNames)) {
                    replace(actionStart + reference.start, actionStart + reference.end, newName)
                }
            }
        }
        // Alternative labels share a grammar-wide namespace even when their rules differ.
        val labels = mutableMapOf<String, String>()
        for (alt in labeledAlternatives) {
            val token = alt.identifier().start
            val label =
                labels.getOrPut(token.text) {
                    val base = "${token.text}_$newName"
                    if (occupied.add(base)) base else nextName(base)
                }
            replace(source.start(token), source.end(token), label)
        }
        GrammarEditPlan(definitionText, changes).resultText()
    }
    val replacement = ((if (producesTokens) listOf(definitionText) else emptyList()) + copies).joinToString("\n\n")
    val edits =
        external.zip(names).map { (reference, newName) ->
            GrammarTextEdit(source.start(reference.symbol), source.end(reference.symbol), newName)
        } + GrammarTextEdit(definitionStart, source.end(definition.stop), replacement)
    val plan = GrammarEditPlan(sourceText, edits)
    if (GrammarSource.parse(plan.resultText()) == null)
        return reject("The copied rules would not form a valid grammar.")
    return DuplicateRuleResult.Ready(plan)
}
