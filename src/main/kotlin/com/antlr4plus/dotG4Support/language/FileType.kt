package com.antlr4plus.dotG4Support.language

import com.antlr4plus.icons.Icons
import com.intellij.openapi.fileTypes.LanguageFileType

class FileType private constructor() : LanguageFileType(ANTLRv4PLanguage.INSTANCE) {
    override fun getName() = "ANTLR v4 grammar file"

    override fun getDescription() = "ANTLR v4 grammar file"

    override fun getDefaultExtension() = "g4"

    override fun getIcon() = Icons.FILE

    companion object {
        @JvmField val INSTANCE: FileType = FileType()
    }
}
