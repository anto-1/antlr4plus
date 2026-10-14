package com.antlr4plus.dotG4Support.language

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.psi.FileViewProvider

class FileRoot(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ANTLRv4PLanguage.INSTANCE) {
    override fun getFileType(): com.intellij.openapi.fileTypes.FileType = FileType.INSTANCE

    override fun toString(): String = "ANTLR v4 grammar file"
}
