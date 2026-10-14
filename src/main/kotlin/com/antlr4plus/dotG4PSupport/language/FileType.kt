package com.antlr4plus.dotG4PSupport.language

import com.antlr4plus.icons.Icons
import com.intellij.openapi.fileTypes.LanguageFileType

class FileType private constructor() : LanguageFileType(ANTLR4PlusLanguage.INSTANCE) {
    override fun getName() = "ANTLR4Plus grammar file"

    override fun getDescription() = "ANTLR4Plus grammar file"

    override fun getDefaultExtension() = "g4p"

    override fun getIcon() = Icons.CYAN_FILE

    companion object {
        @JvmField val INSTANCE: FileType = FileType()
    }
}
