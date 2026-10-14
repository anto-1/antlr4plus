package com.antlr4plus.icons

import com.antlr4plus.dotG4Support.language.FileRoot
import com.antlr4plus.dotG4Support.psi.LexerRuleRefNode
import com.antlr4plus.dotG4Support.psi.ModeSpecNode
import com.antlr4plus.dotG4Support.psi.ParserRuleRefNode
import com.intellij.ide.IconProvider
import com.intellij.psi.PsiElement
import javax.swing.Icon

class IconProvider : IconProvider() {
    override fun getIcon(element: PsiElement, flags: Int): Icon? =
        when (element) {
            is LexerRuleRefNode -> Icons.LEXER_RULE
            is ParserRuleRefNode -> Icons.PARSER_RULE
            is FileRoot -> Icons.FILE
            is ModeSpecNode -> Icons.MODE
            else -> null
        }
}
