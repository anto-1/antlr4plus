package com.antlr4plus.dotG4PSupport.language

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.psi.FileViewProvider

class FileRoot(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ANTLR4PlusLanguage.INSTANCE) {
    override fun getFileType() = FileType.INSTANCE

    override fun toString() = "ANTLR4Plus grammar file"
}
