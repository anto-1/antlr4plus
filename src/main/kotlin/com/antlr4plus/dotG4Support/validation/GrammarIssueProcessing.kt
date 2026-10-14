package com.antlr4plus.dotG4Support.validation

import com.intellij.psi.PsiFile
import java.io.File
import org.antlr.v4.Tool
import org.antlr.v4.tool.GrammarSemanticsMessage
import org.antlr.v4.tool.GrammarSyntaxMessage
import org.antlr.v4.tool.LeftRecursionCyclesMessage
import org.antlr.v4.tool.Rule
import org.antlr.v4.tool.ToolMessage
import org.antlr.v4.tool.ast.GrammarAST
import org.stringtemplate.v4.ST

internal fun processGrammarIssue(file: PsiFile, issue: GrammarIssue) {
    val grammarFile = File(file.virtualFile.path)
    if (issue.msg == null) {
        return
    }

    if (issue.msg.fileName != null) {
        val issueFile = File(issue.msg.fileName)
        if (grammarFile.getName() != issueFile.getName()) {
            return
        }
    }

    var msgST: ST? = null
    when (issue.msg) {
        is GrammarInfoMessage -> { // Supply the template for our custom unused-rule diagnostic.
            val t = issue.msg.offendingToken
            issue.offendingTokens.add(t)
            msgST = ST("unused parser rule <arg>")
            msgST.add("arg", t.text)
            msgST.impl.name = "info"
        }

        is GrammarSemanticsMessage -> {
            val t = issue.msg.offendingToken
            issue.offendingTokens.add(t)
        }

        is LeftRecursionCyclesMessage -> {
            val rulesToHighlight: MutableList<String?> = ArrayList()
            val lmsg: LeftRecursionCyclesMessage = issue.msg
            @Suppress("UNCHECKED_CAST") val cycles = lmsg.getArgs()[0] as MutableCollection<out MutableCollection<Rule>>
            for (cycle in cycles) {
                for (r in cycle) {
                    rulesToHighlight.add(r.name)
                    val nameNode = r.ast.getChild(0) as GrammarAST
                    issue.offendingTokens.add(nameNode.getToken())
                }
            }
        }

        is GrammarSyntaxMessage -> {
            val t = issue.msg.offendingToken
            issue.offendingTokens.add(t)
        }

        is ToolMessage -> {
            issue.offendingTokens.add(issue.msg.offendingToken)
        }
    }

    val antlr = Tool()
    if (msgST == null) {
        msgST = antlr.errMgr.getMessageTemplate(issue.msg)
    }

    if (msgST == null) {
        val errorType = issue.msg.errorType
        if (errorType != null) {
            issue.annotation = "${errorType.severity}: ${errorType.msg}"
        } else {
            issue.annotation = issue.msg.toString()
        }
        return
    }

    var outputMsg = msgST.render()
    if (antlr.errMgr.formatWantsSingleLineMessage()) {
        outputMsg = outputMsg.replace('\n', ' ')
    }
    issue.annotation = outputMsg
}
