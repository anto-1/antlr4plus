package com.antlr4plus.dotG4Support.language

import com.antlr4plus.icons.Icons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object LanguageFileType : LanguageFileType(ANTLRv4PLanguage.INSTANCE) {
    override fun getName(): String = "ANTLRv4PLanguage"

    override fun getDescription(): String = "ANTLR v4 Plus grammar file"

    override fun getDefaultExtension(): String = "g4"

    override fun getIcon(): Icon = Icons.FILE // Adjust if you have a specific icon
}
