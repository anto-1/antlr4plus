package com.antlr4plus.dotG4Support.structureView

import com.antlr4plus.dotG4Support.language.FileRoot
import com.intellij.ide.structureView.*
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class StructureViewFactory : PsiStructureViewFactory {
    class DummyViewTreeElement(psiElement: PsiElement?) : PsiTreeElementBase<PsiElement?>(psiElement) {
        override fun getChildrenBase(): MutableCollection<StructureViewTreeElement?> = mutableListOf()

        override fun getPresentableText(): String = "Sorry .g not supported (use .g4)"
    }

    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder =
        object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModelBase {
                val grammarFile = psiFile.virtualFile
                if (grammarFile == null || !grammarFile.name.endsWith(".g4")) {
                    return StructureViewModelBase(psiFile, DummyViewTreeElement(psiFile))
                }
                return StructureViewModel(psiFile as FileRoot)
            }
        }
}
