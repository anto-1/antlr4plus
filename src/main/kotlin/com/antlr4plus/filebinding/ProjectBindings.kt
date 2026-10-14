package com.antlr4plus.filebinding

import com.antlr4plus.filebinding.BindingOperations.ParsedBinding
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

internal fun collectCurrentBindingsWithProgress(
    projectRoot: VirtualFile,
    indicator: ProgressIndicator,
): List<ParsedBinding> {
    val bindings = mutableListOf<ParsedBinding>()
    var fileCount = 0

    VfsUtilCore.visitChildrenRecursively(
        projectRoot,
        object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (indicator.isCanceled) return false

                fileCount++
                if (fileCount % 100 == 0) {
                    indicator.text2 = "Scanned $fileCount files..."
                    indicator.fraction = (fileCount % 1000) / 1000.0
                }

                if (!file.isDirectory && file.extension?.equals("g4p", ignoreCase = true) == true) {
                    val grammarPath = FileAttributeHelper.readGrammarBinding(file)
                    if (grammarPath != null) {
                        val g4pPath = getProjectRelativePath(projectRoot, file)
                        if (g4pPath != null) {
                            synchronized(bindings) {
                                bindings.add(ParsedBinding(g4pPath, grammarPath))
                            }
                        }
                    }
                }
                return true
            }
        },
    )

    return bindings.sortedBy { it.g4pPath }
}

private fun getProjectRelativePath(projectRoot: VirtualFile, file: VirtualFile): String? =
    try {
        projectRoot.toNioPath().relativize(file.toNioPath()).toString().replace('\\', '/')
    } catch (e: Exception) {
        null
    }

internal fun generateBindingsContent(bindings: List<ParsedBinding>): String = buildString {
    appendLine("# Bindings loaded from project")
    appendLine()
    for ((g4pPath, grammarPath) in bindings) {
        appendLine("$g4pPath -> $grammarPath")
    }
}
