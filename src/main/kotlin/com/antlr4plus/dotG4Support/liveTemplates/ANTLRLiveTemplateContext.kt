package com.antlr4plus.dotG4Support.liveTemplates

import com.antlr4plus.dotG4Support.language.ANTLRv4PLanguage
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilBase

/** Context IDs and inheritance are registered in plugin.xml. */
abstract class ANTLRLiveTemplateContext(presentableName: String) : TemplateContextType(presentableName) {
    protected abstract fun isInContext(file: PsiFile, element: PsiElement, offset: Int): Boolean

    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file
        var offset = templateActionContext.startOffset

        if (!PsiUtilBase.getLanguageAtOffset(file, offset).isKindOf(ANTLRv4PLanguage.INSTANCE)) {
            return false
        }
        if (offset == file.textLength) { // At EOF, inspect the preceding character.
            offset--
        }
        val element = file.findElementAt(offset) ?: return false

        return isInContext(file, element, offset)
    }
}
