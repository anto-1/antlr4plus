package com.antlr4plus.dotG4Support

import com.antlr4plus.dotG4Support.adaptors.ANTLRLexerAdaptorImpl
import com.antlr4plus.dotG4Support.adaptors.ANTLRParserAdaptorImpl
import com.antlr4plus.dotG4Support.ast.createInternalParseTreeNode
import com.antlr4plus.dotG4Support.language.ANTLRv4PLanguage
import com.antlr4plus.dotG4Support.language.FileRoot
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer

class ParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): ANTLRLexerAdaptorImpl {
        val lexer = ANTLRv4Lexer(null)
        return ANTLRLexerAdaptorImpl(lexer)
    }

    override fun createParser(project: Project?): PsiParser = ANTLRParserAdaptorImpl()

    override fun getWhitespaceTokens(): TokenSet = TokenTypes.WHITESPACES

    override fun getCommentTokens(): TokenSet = TokenTypes.COMMENTS

    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY

    override fun getFileNodeType() = FILE

    override fun createFile(viewProvider: FileViewProvider) = FileRoot(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(
        left: ASTNode?,
        right: ASTNode?,
    ): ParserDefinition.SpaceRequirements = ParserDefinition.SpaceRequirements.MAY

    override fun createElement(node: ASTNode): PsiElement = createInternalParseTreeNode(node)
}

val FILE: IFileElementType = IFileElementType(ANTLRv4PLanguage.INSTANCE)
