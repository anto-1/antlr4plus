package com.antlr4plus.parsing

import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.tool.GrammarInterpreterRuleContext

/** Interpreter context with decision-override and tree-diff markers. */
class PreviewInterpreterRuleContext(
    parent: ParserRuleContext?,
    invokingStateNumber: Int,
    ruleIndex: Int,
) : GrammarInterpreterRuleContext(parent, invokingStateNumber, ruleIndex) {
    /** Marks the subtree root containing the overridden decision. */
    var isDecisionOverrideRoot: Boolean = false

    /** Whether the tree-diff walk reached this node. */
    @JvmField var reached: Boolean = false

    /** Returns the recorded outer alternative, including the original left-recursive alternative. */
    override fun getOuterAltNum(): Int = outerAltNum

    override fun equals(other: Any?): Boolean =
        other is PreviewInterpreterRuleContext &&
            (this === other || ruleIndex == other.ruleIndex && outerAltNum == other.outerAltNum)

    override fun hashCode(): Int = ruleIndex shl 7 + outerAltNum
}
