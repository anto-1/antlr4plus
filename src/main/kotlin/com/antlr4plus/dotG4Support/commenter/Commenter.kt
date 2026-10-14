package com.antlr4plus.dotG4Support.commenter

import com.antlr4plus.dotG4Support.TokenTypes
import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.psi.PsiComment
import com.intellij.psi.tree.IElementType
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer

class Commenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix(): String = "//"

    override fun getBlockCommentPrefix(): String = "/*"

    override fun getBlockCommentSuffix(): String = "*/"

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null

    override fun getLineCommentTokenType(): IElementType = TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.LINE_COMMENT]

    override fun getBlockCommentTokenType(): IElementType = TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.BLOCK_COMMENT]

    override fun getDocumentationCommentTokenType(): IElementType =
        TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.DOC_COMMENT]

    override fun getDocumentationCommentPrefix(): String = "/**"

    override fun getDocumentationCommentLinePrefix(): String = "*"

    override fun getDocumentationCommentSuffix(): String = "*/"

    override fun isDocumentationComment(element: PsiComment?): Boolean =
        element != null && element.tokenType === documentationCommentTokenType
}
