package com.antlr4plus.parsing

import org.antlr.v4.Tool
import org.antlr.v4.tool.ANTLRMessage
import org.antlr.v4.tool.DefaultToolListener

/** Collects grammar-loading diagnostics without printing them to stderr. */
class LoadGrammarsToolListener(tool: Tool?) : DefaultToolListener(tool) {
    var grammarErrorMessages: MutableList<String?> = ArrayList()
    var grammarWarningMessages: MutableList<String?> = ArrayList()

    override fun error(msg: ANTLRMessage?) {
        val msgST = tool.errMgr.getMessageTemplate(msg)
        grammarErrorMessages.add(msgST.render())
    }

    override fun warning(msg: ANTLRMessage?) {
        val msgST = tool.errMgr.getMessageTemplate(msg)
        grammarWarningMessages.add(msgST.render())
    }
}
