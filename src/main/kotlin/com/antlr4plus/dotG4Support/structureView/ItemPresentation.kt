package com.antlr4plus.dotG4Support.structureView

import com.antlr4plus.dotG4Support.TokenTypes
import com.antlr4plus.dotG4Support.language.FileRoot
import com.antlr4plus.dotG4Support.psi.GrammarSpecNode
import com.antlr4plus.dotG4Support.psi.ModeSpecNode
import com.antlr4plus.dotG4Support.psi.MyPsiUtils
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon
import org.antlr.intellij.plugin.parser.ANTLRv4Parser

class ItemPresentation(private val element: PsiElement) : ItemPresentation {
    override fun getLocationString(): String? = null

    override fun getPresentableText(): String? {
        if (element is FileRoot) {
            val gnode =
                PsiTreeUtil.findChildOfType(element, GrammarSpecNode::class.java)
                    ?: throw NullPointerException("Missing gnode")
            val id =
                MyPsiUtils.findChildOfType(
                    gnode,
                    TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_identifier],
                )
            if (id != null) {
                return id.text
            }
            return "<n/a>"
        }
        if (element is ModeSpecNode) {
            val mode = element
            val modeId = mode.getNameIdentifier()
            if (modeId != null) {
                return modeId.name
            }
            return "<n/a>"
        }
        return element.node.text
    }

    override fun getIcon(open: Boolean): Icon? = element.getIcon(0)
}
