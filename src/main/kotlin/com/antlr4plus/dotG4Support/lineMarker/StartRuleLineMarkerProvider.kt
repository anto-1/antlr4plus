package com.antlr4plus.dotG4Support.lineMarker

import com.antlr4plus.dotG4Support.psi.GrammarSpecNode
import com.antlr4plus.dotG4Support.psi.ParserRuleSpecNode
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Function as IjFunction
import java.util.function.Supplier

/** Marks the first parser rule in source order, independently of the selected preview start rule. */
class StartRuleLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Attach the marker to the rule-name leaf.
        if (element.firstChild != null) return null

        val containingFile = element.containingFile ?: return null
        val vfile = containingFile.virtualFile ?: containingFile.originalFile.virtualFile
        val ext = vfile?.extension?.lowercase()
        if (ext != "g4") return null

        val ruleSpec = element.parent as? ParserRuleSpecNode ?: return null
        if (ruleSpec.nameIdentifier !== element) return null

        val grammarSpec = PsiTreeUtil.getParentOfType(ruleSpec, GrammarSpecNode::class.java) ?: return null
        val firstParserRule = PsiTreeUtil.findChildOfType(grammarSpec, ParserRuleSpecNode::class.java) ?: return null
        if (firstParserRule !== ruleSpec) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.RunConfigurations.TestState.Run,
            IjFunction<PsiElement, String> { START_RULE_TOOLTIP },
            null as GutterIconNavigationHandler<PsiElement>?, // Non-clickable indicator.
            GutterIconRenderer.Alignment.LEFT,
            Supplier { START_RULE_TOOLTIP },
        )
    }

    companion object {
        private const val START_RULE_TOOLTIP =
            "Start rule. ANTLR uses this as the default entry point when no rule is specified."
    }
}
