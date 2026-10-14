package com.antlr4plus.parsing.actionMap

import com.antlr4plus.util.rethrowIfControlFlow
import java.io.File

internal fun generatedSourceExtension(language: String?): String? =
    when (language?.lowercase()) {
        "java" -> "java"
        "kotlin" -> "kt"
        else -> null
    }

/** Uses the file's absolute path when canonicalization fails. */
internal fun canonicalSourcePath(file: File): String =
    try {
        file.canonicalPath
    } catch (failure: Throwable) {
        rethrowIfControlFlow(failure)
        file.absolutePath
    }

/** Keeps the supplied path text when canonicalization fails. */
internal fun canonicalSourcePath(path: String): String =
    try {
        File(path).canonicalPath
    } catch (failure: Throwable) {
        rethrowIfControlFlow(failure)
        path
    }
