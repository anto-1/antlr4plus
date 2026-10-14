package com.antlr4plus.filebinding

import com.intellij.openapi.vfs.VirtualFile

internal fun buildGrammarDisplayName(
    grammar: VirtualFile,
    grammarsAtLevel: List<VirtualFile>,
): String =
    if (grammarsAtLevel.any { it.name == grammar.name && it != grammar }) {
        "${grammar.name} (${grammar.parent?.name ?: ""})"
    } else {
        grammar.name
    }

/** Negative levels select ancestors; positive levels search descendants at that exact depth. */
internal fun findGrammarsAtLevel(referenceDir: VirtualFile, level: Int): List<VirtualFile> {
    val targetDir =
        when {
            level < 0 -> {
                var dir: VirtualFile? = referenceDir
                repeat(-level) { dir = dir?.parent }
                dir
            }
            level == 0 -> referenceDir
            else -> null
        }

    return when {
        level <= 0 && targetDir != null -> {
            targetDir.children
                .filter { !it.isDirectory && it.extension?.equals("g4", ignoreCase = true) == true }
                .sortedBy { it.name }
        }
        level > 0 -> {
            findGrammarsInSubdirectories(referenceDir, level)
        }
        else -> emptyList()
    }
}

/** Searches direct children of visible sibling folders. */
internal fun findGrammarsInSiblingFolders(referenceDir: VirtualFile): List<VirtualFile> {
    val parent = referenceDir.parent ?: return emptyList()
    return parent.children
        .filter { it.isDirectory && !it.name.startsWith(".") && it != referenceDir }
        .flatMap { sibling ->
            sibling.children.filter {
                !it.isDirectory && it.extension?.equals("g4", ignoreCase = true) == true
            }
        }
        .sortedBy { it.path }
}

private fun findGrammarsInSubdirectories(startDir: VirtualFile, depth: Int): List<VirtualFile> {
    if (depth <= 0) return emptyList()

    val result = mutableListOf<VirtualFile>()

    fun searchAtDepth(dir: VirtualFile, currentDepth: Int) {
        if (currentDepth == depth) {
            dir.children
                .filter { !it.isDirectory && it.extension?.equals("g4", ignoreCase = true) == true }
                .forEach { result.add(it) }
        } else if (currentDepth < depth) {
            dir.children
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .forEach { searchAtDepth(it, currentDepth + 1) }
        }
    }

    searchAtDepth(startDir, 0)
    return result.sortedBy { it.path }
}
