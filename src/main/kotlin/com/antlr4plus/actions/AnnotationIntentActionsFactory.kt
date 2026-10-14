package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.validation.AddTokenDefinitionFix
import com.antlr4plus.dotG4Support.validation.CreateRuleFix
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.antlr.v4.tool.ErrorType
import org.jetbrains.annotations.NotNull

object AnnotationIntentActionsFactory {
    @NotNull
    @JvmStatic
    fun getFix(textRange: TextRange, errorType: ErrorType?, file: PsiFile): IntentionAction? =
        when (errorType) {
            null -> null
            ErrorType.IMPLICIT_TOKEN_DEFINITION -> AddTokenDefinitionFix(textRange)
            ErrorType.UNDEFINED_RULE_REF -> CreateRuleFix(textRange, file)
            else -> null
        }
}
