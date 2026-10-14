package com.antlr4plus.dotG4PSupport.language

import com.intellij.lang.Language

class ANTLR4PlusLanguage : Language("ANTLR4Plus") {
    companion object {
        val INSTANCE = ANTLR4PlusLanguage()
    }

    override fun getDisplayName(): String = "ANTLR4Plus"
}
