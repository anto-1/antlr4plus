package com.antlr4plus.util

import com.intellij.openapi.util.text.StringUtil

/** Escapes untrusted text before embedding it in Swing's HTML label syntax. */
internal fun escapeHtml(text: String): String = StringUtil.escapeXmlEntities(text)

internal fun escapeWhitespace(text: String): String =
    text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
