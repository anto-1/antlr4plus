@file:Suppress("KDocUnresolvedReference")

package com.antlr4plus.dotG4Support.folding

import com.antlr4plus.dotG4Support.TokenTypes
import com.antlr4plus.dotG4Support.TokenTypes.getRuleElementType
import com.antlr4plus.dotG4Support.TokenTypes.getTokenElementType
import com.antlr4plus.dotG4Support.language.FileRoot
import com.antlr4plus.dotG4Support.psi.AtAction
import com.antlr4plus.dotG4Support.psi.GrammarElementRefNode
import com.antlr4plus.dotG4Support.psi.ModeSpecNode
import com.antlr4plus.dotG4Support.psi.MyPsiUtils
import com.antlr4plus.dotG4Support.psi.RuleSpecNode
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.CustomFoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.UnfairTextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.SourceTreeToPsiMap
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser

class ANTLRv4FoldingBuilder : CustomFoldingBuilder() {
    override fun buildLanguageFoldRegions(
        descriptors: MutableList<FoldingDescriptor>,
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ) {
        if (root !is FileRoot) return

        addRuleRefFoldingDescriptors(descriptors, root)

        addActionFoldingDescriptors(descriptors, root)

        addHeaderFoldingDescriptor(descriptors, root, document)

        addCommentDescriptors(descriptors, root)

        addOptionsFoldingDescriptor(descriptors, root)

        addTokensFoldingDescriptor(descriptors, root)

        addModeFoldingDescriptors(descriptors, root)
    }

    override fun getLanguagePlaceholderText(node: ASTNode, range: TextRange): String {
        val element = SourceTreeToPsiMap.treeElementToPsi(node) ?: throw IllegalStateException("Missing psi element")
        return getPlaceholderText(element)
    }

    override fun isRegionCollapsedByDefault(node: ASTNode): Boolean {
        val element = SourceTreeToPsiMap.treeElementToPsi(node) ?: return false

        // Fixed defaults: collapse token declarations, actions and the file header.
        if (node.elementType === TOKENSSPEC || element is AtAction || element is FileRoot) return true
        if (node.elementType === DOC_COMMENT_TOKEN) {
            val parent = element.parent

            if (parent is FileRoot) {
                var firstChild = parent.firstChild
                if (firstChild is PsiWhiteSpace) {
                    firstChild = firstChild.nextSibling
                }
                if (element == firstChild) {
                    return true
                }
            }
        }
        return false
    }
}

private val DOC_COMMENT_TOKEN = getTokenElementType(ANTLRv4Lexer.DOC_COMMENT)
private val BLOCK_COMMENT_TOKEN = getTokenElementType(ANTLRv4Lexer.BLOCK_COMMENT)
private val LINE_COMMENT_TOKEN = getTokenElementType(ANTLRv4Lexer.LINE_COMMENT)

private val OPTIONSSPEC = getRuleElementType(ANTLRv4Parser.RULE_optionsSpec)
private val OPTIONS = getTokenElementType(ANTLRv4Lexer.OPTIONS)

private val TOKENSSPEC = getRuleElementType(ANTLRv4Parser.RULE_tokensSpec)
private val TOKENS = getTokenElementType(ANTLRv4Lexer.TOKENS)

private val RBRACE = getTokenElementType(ANTLRv4Lexer.RBRACE)
private val SEMICOLON = getTokenElementType(ANTLRv4Lexer.SEMI)

private fun addTokensFoldingDescriptor(
    descriptors: MutableList<FoldingDescriptor>,
    root: PsiElement,
) {
    val tokensSpec: PsiElement? = MyPsiUtils.findFirstChildOfType(root, TOKENSSPEC)
    if (tokensSpec != null) {
        val tokens = tokensSpec.firstChild
        if (tokens.node.elementType === TOKENS) {
            val rbrace = tokensSpec.lastChild
            if (rbrace.node.elementType === RBRACE) {
                descriptors.add(
                    FoldingDescriptor(
                        tokensSpec,
                        TextRange(tokens.textRange.endOffset, rbrace.textRange.endOffset),
                    ),
                )
            }
        }
    }
}

private fun addOptionsFoldingDescriptor(
    descriptors: MutableList<FoldingDescriptor>,
    root: PsiElement,
) {
    val optionsSpec: PsiElement? = MyPsiUtils.findFirstChildOfType(root, OPTIONSSPEC)
    if (optionsSpec != null) {
        val options = optionsSpec.firstChild
        if (options.node.elementType === OPTIONS) {
            val rbrace = optionsSpec.lastChild
            if (rbrace.node.elementType === RBRACE) {
                descriptors.add(
                    FoldingDescriptor(
                        optionsSpec,
                        TextRange(options.textRange.endOffset, rbrace.textRange.endOffset),
                    ),
                )
            }
        }
    }
}

private fun addHeaderFoldingDescriptor(
    descriptors: MutableList<FoldingDescriptor>,
    root: PsiElement,
    document: Document,
) {
    val range: TextRange? = getFileHeader(root)
    if (
        range != null &&
            range.length > 1 &&
            document.getLineNumber(range.endOffset) > document.getLineNumber(range.startOffset)
    ) {
        descriptors.add(FoldingDescriptor(root, range))
    }
}

private fun addCommentDescriptors(descriptors: MutableList<FoldingDescriptor>, root: PsiElement) {
    for (comment in MyPsiUtils.findChildrenOfType(root, TokenTypes.COMMENTS)) {
        val type = comment.node.elementType
        if (type === DOC_COMMENT_TOKEN || type === BLOCK_COMMENT_TOKEN) {
            descriptors.add(FoldingDescriptor(comment, comment.textRange))
        }
    }
}

private fun addActionFoldingDescriptors(
    descriptors: MutableList<FoldingDescriptor>,
    root: PsiElement?,
) {
    for (atAction in PsiTreeUtil.findChildrenOfType(root, AtAction::class.java)) {
        val action = atAction.lastChild
        val actionText = action.text
        if (actionText != null && actionText.contains("\n")) {
            descriptors.add(FoldingDescriptor(atAction, action.textRange))
        }
    }
}

private fun addRuleRefFoldingDescriptors(
    descriptors: MutableList<FoldingDescriptor>,
    root: PsiElement?,
) {
    for (specNode in PsiTreeUtil.findChildrenOfType(root, RuleSpecNode::class.java)) {
        val refNode = PsiTreeUtil.findChildOfAnyType(specNode, GrammarElementRefNode::class.java) ?: continue
        val nextSibling = refNode.nextSibling ?: continue
        val startOffset = nextSibling.textOffset

        val backward = TreeUtil.findChildBackward(specNode.node, SEMICOLON) ?: continue
        val endOffset = backward.textRange.endOffset
        if (startOffset >= endOffset) continue

        descriptors.add(FoldingDescriptor(specNode, TextRange(startOffset, endOffset)))
    }
}

private fun addModeFoldingDescriptors(
    descriptors: MutableList<FoldingDescriptor>,
    root: PsiElement?,
) {
    for (specNode in PsiTreeUtil.findChildrenOfType(root, ModeSpecNode::class.java)) {
        val semi = MyPsiUtils.findFirstChildOfType(specNode, getTokenElementType(ANTLRv4Lexer.SEMI))

        if (semi != null) {
            val foldingRange = TextRange.create(semi.textOffset, specNode.node.startOffset + specNode.textLength)
            descriptors.add(FoldingDescriptor(specNode, foldingRange))
        }
    }
}

private fun isComment(element: PsiElement): Boolean {
    val type = element.node.elementType
    return TokenTypes.COMMENTS.contains(type)
}

private fun getFileHeader(file: PsiElement): TextRange? {
    var first = file.firstChild
    if (first is PsiWhiteSpace) first = first.nextSibling
    var element = first
    while (isComment(element)) {
        element = element.nextSibling
        if (element is PsiWhiteSpace) {
            element = element.nextSibling
        } else {
            break
        }
    }
    if (element == null) return null
    if (element.prevSibling is PsiWhiteSpace) element = element.prevSibling
    if (element == null || element == first) return null
    return UnfairTextRange(first.textOffset, element.textOffset)
}

@Suppress("KDocUnresolvedReference")
private fun getPlaceholderText(element: PsiElement): String {
    if (element.node.elementType === LINE_COMMENT_TOKEN) {
        return "//..."
    } else if (element is ModeSpecNode) {
        return ";..."
    } else if (element is RuleSpecNode) {
        return ":...;"
    } else if (element is AtAction) {
        return "{...}"
    }
    return "..."
}
