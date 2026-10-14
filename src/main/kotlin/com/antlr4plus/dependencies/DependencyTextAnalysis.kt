package com.antlr4plus.dependencies

import com.antlr4plus.parsing.GrammarActionAnalyzer

/** Extracts import lines, including Kotlin aliases and Java static or wildcard imports. */
internal fun parseSourceImports(code: String): List<String> {
    val imports = mutableListOf<String>()
    val searchableCode = GrammarActionAnalyzer.stripCommentsAndStrings(code)
    val pattern =
        Regex(
            """^\s*import\s+(static\s+)?([a-zA-Z_][a-zA-Z0-9_.]*(?:\.\*)?)(?:\s+as\s+[a-zA-Z_][a-zA-Z0-9_]*)?\s*;?\s*$""",
            RegexOption.MULTILINE,
        )

    pattern.findAll(searchableCode).forEach { match ->
        val isStatic = match.groupValues[1].isNotEmpty()
        val importPath = match.groupValues[2]
        val isWildcard = importPath.endsWith(".*")
        val dependencyPath =
            when {
                !isStatic -> importPath
                isWildcard -> importPath.removeSuffix(".*")
                else -> importPath.substringBeforeLast('.')
            }
        imports.add(dependencyPath)
    }

    return imports
}

internal fun extractQualifiedClassNameFromContent(content: String, className: String): String? {
    val searchableContent = GrammarActionAnalyzer.stripCommentsAndStrings(content)
    val packagePattern =
        Regex(
            """^\s*package\s+([a-zA-Z_][a-zA-Z0-9_.]*)\s*(?:;|$)""",
            RegexOption.MULTILINE,
        )
    val packageMatch = packagePattern.find(searchableContent)
    return if (packageMatch != null) {
        "${packageMatch.groupValues[1]}.$className"
    } else {
        className
    }
}

internal fun extractGrammarImports(content: String): List<String> {
    val imports = mutableListOf<String>()
    val pattern = Regex("""import\s+([A-Za-z_][A-Za-z0-9_]*(?:\s*,\s*[A-Za-z_][A-Za-z0-9_]*)*)\s*;""")

    pattern.findAll(GrammarActionAnalyzer.stripCommentsAndStrings(content)).forEach { match ->
        val importList = match.groupValues[1]
        importList.split(",").forEach { name ->
            imports.add(name.trim())
        }
    }

    return imports
}

internal fun extractTokenVocab(content: String): String? =
    Regex("""tokenVocab\s*=\s*([A-Za-z_][A-Za-z0-9_]*)\s*;""")
        .find(GrammarActionAnalyzer.stripCommentsAndStrings(content))
        ?.groupValues
        ?.get(1)
