package com.antlr4plus.dotG4Support.formatting

import com.antlr4plus.dotG4Support.language.ANTLRv4PLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor

/** Applies the direct formatter to the entire grammar after the IDE formatting pass. */
class ANTLRv4PostFormatProcessor : PostFormatProcessor {

    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement = source

    override fun processText(
        source: PsiFile,
        rangeToReformat: TextRange,
        settings: CodeStyleSettings,
    ): TextRange {
        if (source.language != ANTLRv4PLanguage.INSTANCE) {
            return rangeToReformat
        }

        val document = source.viewProvider.document ?: return rangeToReformat

        val originalText = document.text
        val formatter = ANTLRv4DirectFormatter(ANTLRv4FormattingSettings())
        val formattedText =
            try {
                formatter.format(originalText)
            } catch (_: Exception) {
                return rangeToReformat
            }

        if (formattedText != originalText) {
            document.setText(formattedText)
            return TextRange(0, formattedText.length)
        }

        return rangeToReformat
    }
}
