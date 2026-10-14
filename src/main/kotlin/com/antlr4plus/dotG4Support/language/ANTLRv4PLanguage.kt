package com.antlr4plus.dotG4Support.language

import com.intellij.lang.Language

class ANTLRv4PLanguage : Language("ANTLRv4P") {
    companion object {
        val INSTANCE = ANTLRv4PLanguage()
    }

    override fun getDisplayName(): String = "ANTLRv4P"
}
