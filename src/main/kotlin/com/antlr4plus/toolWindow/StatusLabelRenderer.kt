package com.antlr4plus.toolWindow

import com.antlr4plus.state.PreviewState
import com.antlr4plus.util.escapeHtml
import com.intellij.openapi.vfs.VirtualFile
import java.awt.FontMetrics
import javax.swing.JLabel

sealed interface Region {
    val startX: Int
    val endX: Int
}

data class FileRegion(
    override val startX: Int,
    override val endX: Int,
    val file: VirtualFile,
) : Region

internal class StatusLabelRenderer {
    private var cachedMetrics: FontMetrics? = null
    private var cachedLinkSymbolWidth = 0
    private var cachedSpaceWidth = 0
    private var cachedImportIconWidth = 0
    private val stringWidthCache = mutableMapOf<String, Int>()

    private fun getStringWidth(metrics: FontMetrics, text: String): Int =
        stringWidthCache.getOrPut(text) { metrics.stringWidth(text) }

    fun update(
        state: PreviewState,
        statusLabel: JLabel,
        resolvedImportedFiles: Set<VirtualFile> = emptySet(),
    ): List<Region> {
        val linkSymbol = "🔗"
        val regions = mutableListOf<Region>()
        val importedFiles =
            resolvedImportedFiles.toMutableSet().apply {
                state.grammarFile?.let { remove(it) }
                state.lexerGrammarFile?.let { remove(it) }
                state.g4pInputFile?.let { remove(it) }
            }

        val htmlText =
            state.g4pInputFile?.let { g4p ->
                if (state.grammarFile == null && state.lexerGrammarFile == null) {
                    "<html><nobr><span style='color:rgb(42,172,184);'>${escapeHtml(g4p.name)}</span> <span style='color:#EAB308;'>(no grammar)</span></nobr></html>"
                } else {
                    val parts =
                        mutableListOf(
                            "<span style='color:rgb(42,172,184);'>${escapeHtml(g4p.name)}</span>",
                        )
                    state.grammarFile?.let { parser ->
                        parts.add(
                            "$linkSymbol <span style='color:rgb(199,125,187);'>${escapeHtml(parser.name)}</span>",
                        )
                    }
                    state.lexerGrammarFile?.let { lexer ->
                        if (lexer.path != (state.grammarFile?.path ?: "")) {
                            parts.add(
                                "$linkSymbol <span style='color:rgb(199,125,187);'>${escapeHtml(lexer.name)}</span>",
                            )
                        }
                    }

                    if (importedFiles.isNotEmpty()) {
                        parts.add(" <span style='color:#A0A0A0;'>\uD83D\uDCE5</span> ")
                        val importParts =
                            importedFiles
                                .sortedBy { it.name }
                                .map {
                                    "<span style='color:rgb(199,125,187);'>${escapeHtml(it.name)}</span>"
                                }
                        parts.add(importParts.joinToString(" $linkSymbol "))
                    }

                    "<html><nobr>${parts.joinToString(" ")}</nobr></html>"
                }
            } ?: "<html><nobr><span style='color:#808080;'>Ready</span></nobr></html>"

        statusLabel.text = htmlText

        state.g4pInputFile?.let { g4p ->
            if (state.grammarFile != null || state.lexerGrammarFile != null) {
                val metrics =
                    cachedMetrics
                        ?: statusLabel.getFontMetrics(statusLabel.font).also {
                            cachedMetrics = it
                            cachedLinkSymbolWidth = it.stringWidth(" $linkSymbol ")
                            cachedSpaceWidth = it.stringWidth(" ")
                            cachedImportIconWidth = it.stringWidth(" 📥 ")
                        }
                var currentX = statusLabel.border?.getBorderInsets(statusLabel)?.left ?: 5

                val g4pWidth = getStringWidth(metrics, g4p.name)
                regions.add(FileRegion(currentX, currentX + g4pWidth, g4p))
                currentX += g4pWidth + cachedLinkSymbolWidth

                state.grammarFile?.let { parser ->
                    val parserWidth = getStringWidth(metrics, parser.name)
                    regions.add(FileRegion(currentX, currentX + parserWidth, parser))
                    currentX += parserWidth + cachedLinkSymbolWidth
                }

                state.lexerGrammarFile?.let { lexer ->
                    if (lexer.path != (state.grammarFile?.path ?: "")) {
                        val lexerWidth = getStringWidth(metrics, lexer.name)
                        regions.add(FileRegion(currentX, currentX + lexerWidth, lexer))
                        currentX += lexerWidth + cachedSpaceWidth
                    }
                }

                if (importedFiles.isNotEmpty()) {
                    currentX += cachedImportIconWidth

                    var first = true
                    for (imp in importedFiles.sortedBy { it.name }) {
                        if (!first) {
                            currentX += cachedLinkSymbolWidth
                        }
                        val impWidth = getStringWidth(metrics, imp.name)
                        regions.add(FileRegion(currentX, currentX + impWidth, imp))
                        currentX += impWidth
                        first = false
                    }
                }
            }
        }

        return regions
    }
}
