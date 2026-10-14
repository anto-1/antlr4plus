package com.antlr4plus.dotG4Support.adaptors

import com.antlr4plus.dotG4Support.TokenTypes
import com.antlr4plus.dotG4Support.language.ANTLRv4PLanguage
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import org.antlr.intellij.adaptor.parser.ANTLRParserAdaptor
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree

class ANTLRParserAdaptorImpl : ANTLRParserAdaptor(ANTLRv4PLanguage.INSTANCE, ANTLRv4Parser(null)) {
    override fun parse(parser: Parser, root: IElementType): ParseTree {
        val startRule =
            when (root) {
                is IFileElementType -> ANTLRv4Parser.RULE_grammarSpec
                TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.TOKEN_REF],
                TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.RULE_REF] -> ANTLRv4Parser.RULE_atom
                else -> Token.INVALID_TYPE
            }

        return when (startRule) {
            ANTLRv4Parser.RULE_grammarSpec -> (parser as ANTLRv4Parser).grammarSpec()
            ANTLRv4Parser.RULE_atom -> (parser as ANTLRv4Parser).atom()
            else -> throw UnsupportedOperationException("cannot start parsing using root element $root")
        }
    }
}
