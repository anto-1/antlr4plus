package com.antlr4plus.parsing

import com.antlr4plus.error.ComposeAntlrErrorConsolePanel
import com.intellij.openapi.project.Project
import org.antlr.v4.Tool
import org.antlr.v4.tool.ANTLRMessage
import org.antlr.v4.tool.ANTLRToolListener

/** Collects ANTLR output for the console and editor inspections. */
class ANTLRToolListenerImpl(
    var tool: Tool,
    var consolePanel: ComposeAntlrErrorConsolePanel?,
    private val project: Project? = null,
    private val isCurrent: () -> Boolean = { true },
) : ANTLRToolListener {
    val all: MutableList<String> = ArrayList()
    val errors: MutableList<String> = ArrayList()
    val warnings: MutableList<String> = ArrayList()
    var hasOutput: Boolean = false

    override fun info(msg: String) {
        if (isCurrent()) consolePanel?.appendMultiLine(msg, false)
        all.add(msg)
        hasOutput = true
    }

    override fun error(msg: ANTLRMessage?) {
        track(msg, true)
    }

    override fun warning(msg: ANTLRMessage?) {
        track(msg, false)
    }

    private fun track(msg: ANTLRMessage?, isError: Boolean) {
        msg ?: return
        val msgST = tool.errMgr.getMessageTemplate(msg)
        val renderedMessage = msgST.render()

        if (isCurrent()) consolePanel?.appendMultiLine(renderedMessage, isError)
        all.add(renderedMessage)
        if (isError) {
            errors.add(renderedMessage)
        } else {
            warnings.add(renderedMessage)
        }
        hasOutput = true

        project
            ?.takeIf { isCurrent() }
            ?.let { proj ->
                val warningsService = GrammarWarningsService.getInstance(proj)
                warningsService.addFromAntlrMessage(msg, renderedMessage, isError)
                msg.fileName?.let { fileName ->
                    warningsService.triggerReAnnotation(fileName)
                }
            }
    }
}
