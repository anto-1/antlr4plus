package com.antlr4plus.dotG4Support.validation

import com.intellij.openapi.diagnostic.Logger
import org.antlr.v4.tool.ANTLRMessage
import org.antlr.v4.tool.ANTLRToolListener

/** Collects ANTLR messages for grammar editor annotations. */
internal class GrammarIssuesCollectorToolListener : ANTLRToolListener {
    companion object {
        private val LOG = Logger.getInstance(GrammarIssuesCollectorToolListener::class.java)
    }

    @JvmField val issues: MutableList<GrammarIssue> = ArrayList()

    override fun info(msg: String?) {
        LOG.info("ANTLRToolListener.info: $msg")
    }

    override fun error(msg: ANTLRMessage) {
        LOG.info(
            "ANTLRToolListener.error: ${msg.errorType} at ${msg.fileName}:${msg.line}:${msg.charPosition}",
        )
        issues.add(GrammarIssue(msg))
    }

    override fun warning(msg: ANTLRMessage) {
        LOG.info(
            "ANTLRToolListener.warning: ${msg.errorType} at ${msg.fileName}:${msg.line}:${msg.charPosition}",
        )
        issues.add(GrammarIssue(msg))
    }
}
