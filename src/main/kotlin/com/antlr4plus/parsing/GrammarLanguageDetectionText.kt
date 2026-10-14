package com.antlr4plus.parsing

/** Collects action text using brace and regex heuristics for language detection. */
internal fun extractActionBlocks(content: String): String = buildString {
    val namedActionPattern = Regex("""@\w+::\w+\s*\{""")
    var idx = 0
    while (idx < content.length) {
        val match = namedActionPattern.find(content, idx) ?: break

        val braceStart = match.range.last
        val braceEnd = findMatchingBrace(content, braceStart)
        if (braceEnd > braceStart) {
            append(content.substring(braceStart + 1, braceEnd))
            append("\n")
            idx = braceEnd + 1
        } else {
            idx = match.range.last + 1
        }
    }

    val inlineActionPattern = Regex("""\{[^{}]+}""")
    for (match in inlineActionPattern.findAll(content)) {
        val actionText = match.value
        if (!actionText.contains("tokens") && !actionText.contains("options")) {
            append(actionText)
            append("\n")
        }
    }
}

private fun findMatchingBrace(content: String, openBraceIndex: Int): Int {
    var depth = 1
    var i = openBraceIndex + 1
    while (i < content.length && depth > 0) {
        when (content[i]) {
            '{' -> depth++
            '}' -> depth--
        }
        i++
    }
    return if (depth == 0) i - 1 else -1
}

/** Reads the superclass option with text searches, without lexical validation. */
internal fun extractSuperClassName(content: String): String? {
    val superClassIndex = content.indexOf("superClass")
    if (superClassIndex == -1) return null

    val equalsIndex = content.indexOf('=', superClassIndex)
    if (equalsIndex == -1 || equalsIndex - superClassIndex > 15) return null

    val semicolonIndex = content.indexOf(';', equalsIndex)
    if (semicolonIndex == -1) return null

    return content.substring(equalsIndex + 1, semicolonIndex).trim()
}
