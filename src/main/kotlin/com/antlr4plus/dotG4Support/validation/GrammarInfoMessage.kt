package com.antlr4plus.dotG4Support.validation

import org.antlr.runtime.Token
import org.antlr.v4.tool.GrammarSemanticsMessage

class GrammarInfoMessage(fileName: String?, offendingToken: Token?, vararg args: Any?) :
    GrammarSemanticsMessage(null, fileName, offendingToken, *args)
