package com.antlr4plus.parsing

import java.io.File
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import org.antlr.v4.tool.Grammar

/** Collects explicit grammar files and loaded imports without VFS access or canonical-path I/O. */
internal object GrammarDependencyFiles {

    fun collect(
        parserGrammarFile: File,
        lexerGrammarFile: File?,
        parserGrammar: Grammar?,
        lexerGrammar: Grammar?,
    ): List<File> {
        val filesByPath = linkedMapOf<Path, File>()
        val normalizedParser = normalize(parserGrammarFile, null)
        val normalizedLexer = lexerGrammarFile?.let { normalize(it, null) }

        fun add(file: File?, baseDirectory: File?, requireGrammarExtension: Boolean = true) {
            file ?: return
            if (requireGrammarExtension && !file.name.endsWith(".g4", ignoreCase = true)) return
            val normalized = normalize(file, baseDirectory)
            filesByPath.putIfAbsent(normalized.toPath(), normalized)
        }

        val visitedGrammars = Collections.newSetFromMap(IdentityHashMap<Grammar, Boolean>())

        fun addGrammarAndImports(grammar: Grammar?, grammarFile: File) {
            grammar ?: return
            if (!visitedGrammars.add(grammar)) return
            add(grammarFile, null)

            grammar.importedGrammars?.forEach { imported ->
                val importedFile =
                    imported.fileName
                        ?.let(::File)
                        ?.takeIf { it.name.endsWith(".g4", ignoreCase = true) }
                        ?.let { normalize(it, grammarFile.parentFile) } ?: return@forEach
                add(importedFile, null)
                addGrammarAndImports(imported, importedFile)
            }
        }

        add(normalizedParser, null, requireGrammarExtension = false)
        add(normalizedLexer, null, requireGrammarExtension = false)
        addGrammarAndImports(parserGrammar, normalizedParser)
        addGrammarAndImports(lexerGrammar, normalizedLexer ?: normalizedParser)
        return filesByPath.values.toList()
    }

    fun contains(
        changedFile: File,
        parserGrammarFile: File,
        lexerGrammarFile: File?,
        parserGrammar: Grammar?,
        lexerGrammar: Grammar?,
    ): Boolean {
        val changedPath = normalize(changedFile, null).toPath()
        return collect(
                parserGrammarFile,
                lexerGrammarFile,
                parserGrammar,
                lexerGrammar,
            )
            .any { it.toPath() == changedPath }
    }

    private fun normalize(file: File, baseDirectory: File?): File {
        val supplied = file.toPath()
        val absolute =
            when {
                supplied.isAbsolute -> supplied
                baseDirectory != null -> baseDirectory.toPath().resolve(supplied)
                else -> supplied.toAbsolutePath()
            }
        return absolute.normalize().toFile()
    }
}
