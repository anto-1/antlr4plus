package com.antlr4plus.dotG4PSupport.language

import com.antlr4plus.icons.Icons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object LanguageFileType : LanguageFileType(ANTLR4PlusLanguage.INSTANCE) {
    override fun getName(): String = "ANTLR4PlusFile"

    override fun getDescription(): String = "ANTLR4Plus source file"

    override fun getDefaultExtension(): String = "g4p"

    override fun getIcon(): Icon = Icons.CYAN_FILE
}
