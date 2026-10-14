package com.antlr4plus.dotG4Support.validation

import org.antlr.runtime.Token
import org.antlr.v4.tool.ANTLRMessage

class GrammarIssue(@JvmField val msg: ANTLRMessage?) {
    @JvmField var annotation: String? = null
    @JvmField val offendingTokens: MutableList<Token?> = ArrayList()
}
