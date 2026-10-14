package com.antlr4plus.dotG4Support.psi

import com.antlr4plus.dotG4Support.TokenTypes
import com.antlr4plus.dotG4Support.language.ANTLRv4PLanguage
import com.antlr4plus.dotG4Support.language.FileRoot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.PsiFileFactoryImpl
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiElementFilter
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.plugin.parser.ANTLRv4Parser

object MyPsiUtils {
    @JvmStatic
    fun findFirstChildOfType(parent: PsiElement, type: IElementType): PsiElement? =
        findFirstChildOfType(parent, TokenSet.create(type))

    /** Returns the first matching element in the PSI subtree. */
    @JvmStatic
    fun findFirstChildOfType(parent: PsiElement, types: TokenSet): PsiElement? {
        val iterator = findChildrenOfType(parent, types).iterator()
        if (iterator.hasNext()) return iterator.next()
        return null
    }

    /** Collects elements whose types match the token set. */
    @JvmStatic
    fun findChildrenOfType(parent: PsiElement, types: TokenSet): Iterable<PsiElement> {
        val psiElements =
            PsiTreeUtil.collectElements(
                parent,
                PsiElementFilter collectElements@{ input: PsiElement ->
                    val node = input.node ?: return@collectElements false
                    types.contains(node.elementType)
                },
            )
        return listOf<PsiElement>(*psiElements)
    }

    /** Search the whole grammar because modes contain additional lexer rules. */
    @JvmStatic
    fun findSpecNode(grammar: GrammarSpecNode?, ruleName: String?): PsiElement? {
        val definitionFilter = PsiElementFilter { element1: PsiElement? ->
            if (element1 !is RuleSpecNode) {
                return@PsiElementFilter false
            }
            val id = element1.getNameIdentifier()
            id != null && id.text == ruleName
        }

        return PsiTreeUtil.collectElements(grammar, definitionFilter).firstOrNull()
    }

    @JvmStatic
    fun createLeafFromText(
        project: Project,
        context: PsiElement?,
        text: String?,
        type: IElementType,
    ): PsiElement {
        val factory = PsiFileFactory.getInstance(project) as PsiFileFactoryImpl
        val el =
            factory.createElementFromText(
                text,
                ANTLRv4PLanguage.INSTANCE,
                type,
                context,
            ) ?: throw IllegalStateException("Missing createElementFromText result")
        return PsiTreeUtil.getDeepestFirst(el) // Force parsing of the temporary file.
    }

    @JvmStatic
    fun replacePsiFileFromText(project: Project, psiFile: PsiFile, text: String) {
        val newPsiFile = createFile(project, text)
        WriteCommandAction.runWriteCommandAction(
            project,
            "Generate Lexer Rules",
            null,
            {
                psiFile.deleteChildRange(psiFile.firstChild, psiFile.lastChild)
                psiFile.addRange(newPsiFile.firstChild, newPsiFile.lastChild)
            },
            psiFile,
        )
    }

    fun createFile(project: Project, text: String): PsiFile {
        val fileName = "a.g4" // The extension selects grammar handling.
        val factory = PsiFileFactory.getInstance(project) as PsiFileFactoryImpl
        return factory.createFileFromText(
            fileName,
            ANTLRv4PLanguage.INSTANCE,
            text,
            false,
            false,
        ) ?: error("Failed to create PsiFile from text")
    }

    /** Collects nodes whose element-type name matches tokenText. */
    fun collectNodesWithName(root: PsiElement?, tokenText: String?): Array<PsiElement> =
        PsiTreeUtil.collectElements(root) { element: PsiElement ->
            val tokenTypeName = element.node.elementType.toString()
            tokenTypeName == tokenText
        }

    fun collectChildrenOfType(root: PsiElement, tokenType: IElementType?): Array<PsiElement> {
        val elements: MutableList<PsiElement> = ArrayList()
        for (child in root.children) {
            if (child.node.elementType === tokenType) {
                elements.add(child)
            }
        }
        return elements.toTypedArray()
    }

    fun findChildOfType(root: PsiElement, tokenType: IElementType?): PsiElement? =
        root.children.firstOrNull { it.node.elementType === tokenType }

    fun collectChildrenWithText(root: PsiElement, text: String?): Array<PsiElement> {
        val elements: MutableList<PsiElement> = ArrayList()
        for (child in root.children) {
            if (child.text == text) {
                elements.add(child)
            }
        }
        return elements.toTypedArray()
    }

    fun findTokenVocabIfAny(file: FileRoot?): String? {
        var vocabName: String? = null
        val options = collectNodesWithName(file, "option")
        for (o in options) {
            val tokenVocab = collectChildrenWithText(o, "tokenVocab")
            if (tokenVocab.isNotEmpty()) {
                val optionNode = tokenVocab[0].parent
                val ids =
                    collectChildrenOfType(
                        optionNode,
                        TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_optionValue],
                    )
                vocabName = ids[0].text
            }
        }
        return vocabName
    }

    fun findElement(startNode: PsiElement?, offset: Int): PsiElement? {
        if (startNode == null) return null

        var c = startNode.firstChild
        while (c != null) {
            val result = findElement(c, offset)
            if (result != null) {
                return result
            }
            c = c.nextSibling
        }
        return null
    }
}
