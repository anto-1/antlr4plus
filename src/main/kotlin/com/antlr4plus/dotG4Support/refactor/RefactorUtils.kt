package com.antlr4plus.dotG4Support.refactor

import com.antlr4plus.parsing.ParsingUtils
import java.util.*
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode
import org.antlr.v4.runtime.tree.xpath.XPath
import org.antlr.v4.tool.Grammar
import org.stringtemplate.v4.misc.Misc

object RefactorUtils {
    val literalToRuleNameMap: MutableMap<String?, String?> =
        object : HashMap<String?, String?>() {
            init {
                put("'('", "LPAREN")
                put("')'", "RPAREN")
                put("'{'", "LBRACE")
                put("'}'", "RBRACE")
                put("'['", "LBRACK")
                put("']'", "RBRACK")
                put("';'", "SEMI")
                put("','", "COMMA")
                put("'.'", "DOT")
                put("'='", "ASSIGN")
                put("'>'", "GT")
                put("'<'", "LT")
                put("'!'", "BANG")
                put("'~'", "TILDE")
                put("'?'", "QUESTION")
                put("':'", "COLON")
                put("'=='", "EQUAL_EQUAL")
                put("'='", "EQUAL")
                put("'<='", "LE")
                put("'>='", "GE")
                put("'!='", "NOT_EQUAL")
                put("'&&'", "AND")
                put("'||'", "OR")
                put("'++'", "INC")
                put("'--'", "DEC")
                put("'+'", "ADD")
                put("'-'", "SUB")
                put("'*'", "MUL")
                put("'/'", "DIV")
                put("'&'", "BITAND")
                put("'|'", "BITOR")
                put("'^'", "CARET")
                put("'%'", "MOD")
                put("'+='", "ADD_ASSIGN")
                put("'-='", "SUB_ASSIGN")
                put("'*='", "MUL_ASSIGN")
                put("'/='", "DIV_ASSIGN")
                put("'&='", "AND_ASSIGN")
                put("'|='", "OR_ASSIGN")
                put("'^='", "XOR_ASSIGN")
                put("'%='", "MOD_ASSIGN")
                put("'<<='", "LSHIFT_ASSIGN")
                put("'>>='", "RSHIFT_ASSIGN")
                put("'>>>='", "URSHIFT_ASSIGN")
                put("'@'", "AT")
                put("'...'", "ELLIPSIS")
                put("'\\'", "BACKSLASH")
            }
        }

    @JvmStatic
    fun getLexerRuleNameFromLiteral(literal: String): String {
        val name = literalToRuleNameMap[literal]
        if (name != null) {
            return name
        }
        val candidate = if (literal.length >= 2) Misc.strip(literal, 1).uppercase(Locale.ROOT) else ""
        val tokens = ParsingUtils.tokenizeANTLRGrammar(candidate).tokens
        // ANTLR needs its T__ namespace for literals the user does not select.
        return if (
            !candidate.startsWith("T__") &&
                tokens.size == 2 &&
                tokens[0].type == ANTLRv4Lexer.TOKEN_REF &&
                tokens[0].text == candidate
        )
            candidate
        else "LITERAL"
    }

    @JvmStatic
    fun getRuleDefNameNode(parser: Parser?, tree: ParseTree?, ruleName: String): TerminalNode? {
        val ruleDefRuleNodes: MutableCollection<ParseTree>
        if (Grammar.isTokenName(ruleName)) {
            ruleDefRuleNodes = XPath.findAll(tree, "//lexerRule/TOKEN_REF", parser)
        } else {
            ruleDefRuleNodes = XPath.findAll(tree, "//parserRuleSpec/RULE_REF", parser)
        }
        for (node in ruleDefRuleNodes) {
            val r = node.text
            if (r == ruleName) {
                return node as TerminalNode
            }
        }
        return null
    }

    @JvmStatic
    fun ruleHasMultipleOutermostAlts(parser: Parser?, ruleTree: ParseTree?): Boolean =
        XPath.findAll(ruleTree, "/parserRuleSpec/ruleBlock/ruleAltList/OR", parser).isNotEmpty() ||
            XPath.findAll(ruleTree, "/lexerRule/lexerRuleBlock/lexerAltList/OR", parser).isNotEmpty()

    /** Returns the rule body between colon and semicolon, trimming surrounding off-channel tokens. */
    @JvmStatic
    fun getRuleText(tokens: CommonTokenStream, ruleDefNode: ParserRuleContext): String? {
        val semi = ruleDefNode.getToken(ANTLRv4Parser.SEMI, 0)?.symbol ?: return null
        val colon = ruleDefNode.getToken(ANTLRv4Parser.COLON, 0)?.symbol ?: return null
        val beforeSemi = tokens.get(semi.tokenIndex - 1)
        val afterColon = tokens.get(colon.tokenIndex + 1)

        val ignoreBefore = tokens.getHiddenTokensToRight(colon.tokenIndex)
        val ignoreAfter: List<Token>? = tokens.getHiddenTokensToLeft(semi.tokenIndex)
        var textStart = afterColon
        var textStop = beforeSemi
        if (ignoreBefore != null) {
            val lastWSAfterColon = ignoreBefore[ignoreBefore.size - 1]
            textStart = tokens.get(lastWSAfterColon.tokenIndex + 1)
        }
        if (ignoreAfter != null) {
            val firstWSAtEndOfRule = ignoreAfter[0].tokenIndex - 1
            textStop = tokens.get(firstWSAtEndOfRule)
        }

        return tokens.getText(textStart, textStop)
    }

    @JvmStatic
    fun getAllRuleRefNodes(
        parser: Parser?,
        tree: ParseTree?,
        ruleName: String,
    ): MutableList<TerminalNode>? {
        val nodes: MutableList<TerminalNode> = ArrayList<TerminalNode>()
        val ruleRefs: MutableCollection<ParseTree>
        if (Grammar.isTokenName(ruleName)) {
            ruleRefs = XPath.findAll(tree, "//lexerRuleBlock//TOKEN_REF", parser)
        } else {
            ruleRefs = XPath.findAll(tree, "//ruleBlock//RULE_REF", parser)
        }
        for (node in ruleRefs) {
            val terminal = node as TerminalNode
            // Identifiers in labels, options and lexer commands are not rule invocations.
            val parent = terminal.parent
            if (
                parent !is ANTLRv4Parser.RulerefContext &&
                    parent !is ANTLRv4Parser.TerminalContext &&
                    parent !is ANTLRv4Parser.SetElementContext
            )
                continue
            val rrefToken = terminal.symbol
            val r = rrefToken.text
            if (r == ruleName) {
                nodes.add(terminal)
            }
        }
        if (nodes.isEmpty()) return null
        return nodes
    }
}
