package com.antlr4plus.previewService

import com.antlr4plus.parsing.CaseChangingStrategy
import com.antlr4plus.parsing.ParsingResult
import com.antlr4plus.parsing.PreviewParser
import com.antlr4plus.preview.AltLabelTextProvider
import com.antlr4plus.preview.hierarchy.HierarchyViewer
import com.antlr4plus.preview.tree.BuchheimWalkerLayout
import com.antlr4plus.preview.tree.DefaultTreeTextProvider
import com.antlr4plus.preview.tree.TreeViewer
import com.antlr4plus.timeline.TokenTimeline
import com.antlr4plus.timeline.TokenTimelineBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import java.awt.geom.Rectangle2D
import java.nio.charset.Charset
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.antlr.v4.gui.TreeTextProvider
import org.antlr.v4.runtime.misc.Utils
import org.antlr.v4.runtime.tree.Tree
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.LexerGrammar

/** Immutable preview inputs captured before background work begins. */
internal data class PreviewRequest(
    val processingId: Long,
    val g4pFile: VirtualFile,
    val grammarFile: VirtualFile,
    val lexerGrammarFile: VirtualFile?,
    val grammar: Grammar?,
    val lexerGrammar: LexerGrammar?,
    val startRuleName: String?,
    val selectedLanguage: String?,
    val previewRuntime: String,
    val grammarCharset: Charset,
    val caseChangingStrategy: CaseChangingStrategy,
    val codeGenerationArgs: List<String>,
    val sourcePath: String,
    val targetOutputDir: String,
    val targetLibDir: String?,
) {
    val isLexerOnlyMode: Boolean
        get() = lexerGrammar != null && grammar == null

    val packageName: String?
        get() {
            val index = codeGenerationArgs.indexOf("-package")
            return if (index >= 0) codeGenerationArgs.getOrNull(index + 1) else null
        }
}

/** Background-only data handed to the EDT as one coherent presentation snapshot. */
internal data class PreviewPresentation(
    val treeLayout: BuchheimWalkerLayout<Tree>?,
    val subtreeBounds: Map<Tree, Rectangle2D.Double>?,
    val hierarchyTree: DefaultMutableTreeNode?,
    val tokenTimeline: TokenTimeline?,
)

/** Builds every expensive view model from one immutable [PreviewRequest]. */
internal object PreviewComputation {
    private val log = Logger.getInstance(PreviewComputation::class.java)

    suspend fun build(
        request: PreviewRequest,
        parsingResult: ParsingResult,
        inputText: String,
        treeViewer: TreeViewer?,
        fontMetrics: TreeViewer.FontMetricsSnapshot?,
        hierarchyViewer: HierarchyViewer?,
        buildTree: Boolean,
        buildHierarchy: Boolean,
    ): PreviewPresentation =
        withContext(Dispatchers.Default) {
            check(!SwingUtilities.isEventDispatchThread()) {
                "Preview computation must not run on the EDT"
            }

            ensureActive()
            val javaTree = parsingResult.javaTree
            val textProvider = textProvider(request, parsingResult)
            val nodeText: (Tree?) -> String = { node ->
                Utils.escapeWhitespace(textProvider?.getText(node), true)
            }

            val cachedExtents =
                if (buildTree && fontMetrics != null && javaTree != null) {
                    treeViewer?.computeNodeExtentsWithSnapshot(javaTree, fontMetrics, nodeText)
                } else {
                    null
                }

            ensureActive()
            val layout =
                if (buildTree && cachedExtents != null && javaTree != null) {
                    treeViewer?.computeTreeLayoutWithCachedExtents(javaTree, cachedExtents)
                } else {
                    null
                }

            ensureActive()
            val subtreeBounds =
                if (buildTree && layout != null && javaTree != null) {
                    treeViewer?.computeSubtreeBoundsWithLayout(javaTree, layout)
                } else {
                    null
                }

            ensureActive()
            val hierarchyTree =
                if (buildHierarchy && javaTree != null) {
                    hierarchyViewer?.wrapTree(javaTree)
                } else {
                    null
                }

            ensureActive()
            val timeline =
                request.lexerGrammar?.let { lexer ->
                    val strategy =
                        CaseChangingStrategy.getEffectiveStrategy(
                            lexer,
                            request.caseChangingStrategy,
                        )
                    TokenTimelineBuilder.buildTimeline(lexer, inputText, strategy)
                }

            PreviewPresentation(layout, subtreeBounds, hierarchyTree, timeline)
        }

    private fun textProvider(
        request: PreviewRequest,
        parsingResult: ParsingResult,
    ): TreeTextProvider? {
        val provider =
            (parsingResult.parser as? PreviewParser)?.let { parser ->
                request.grammar?.let { grammar -> AltLabelTextProvider(parser, grammar) }
            }
                ?: run {
                    val ruleNames = parsingResult.ruleNames?.toList() ?: request.grammar?.getRuleNames()?.toList()
                    val vocabulary = parsingResult.vocabulary
                    val kotlinVocabulary = vocabulary.takeIf { parsingResult.isKotlin }
                    ruleNames?.let {
                        DefaultTreeTextProvider(it, vocabulary, kotlinVocabulary)
                    }
                }

        log.debug(
            "Preview labels: provider=${provider?.javaClass?.simpleName}, " + "rules=${parsingResult.ruleNames?.size}",
        )
        return provider
    }
}
