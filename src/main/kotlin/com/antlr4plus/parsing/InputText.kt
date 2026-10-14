package com.antlr4plus.parsing

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText

/** Captures current editor text, falling back to the file charset when no document is available. */
internal fun readInputText(inputFile: VirtualFile): String =
    ReadAction.compute<String, Throwable> {
        FileDocumentManager.getInstance().getDocument(inputFile)?.text ?: inputFile.readText()
    }
