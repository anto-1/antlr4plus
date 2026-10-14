package com.antlr4plus.dependencies.ui

import com.antlr4plus.dependencies.UnifiedDependencyResolver
import com.antlr4plus.parsing.embedded.EmbeddedCode
import com.intellij.icons.AllIcons
import java.io.File
import javax.swing.Icon

/** Builds a display tree from the resolved grammar and source dependencies. */
object DependencyGraphModel {

    enum class NodeType(val icon: Icon) {
        GRAMMAR_FILE(AllIcons.FileTypes.Any_type),
        JAVA_FILE(AllIcons.FileTypes.Java),
        KOTLIN_FILE(AllIcons.FileTypes.Any_type),
        EMBEDDED_CODE(AllIcons.Nodes.Method),
        UNRESOLVED_IMPORT(AllIcons.General.Warning),
    }

    data class DependencyNode(
        val type: NodeType,
        val name: String,
        val file: File? = null,
        val details: String? = null,
        val children: MutableList<DependencyNode> = mutableListOf(),
        /** How this node was reached: import, tokenVocab or source reference. */
        val reachability: String? = null,
        val codeContent: String? = null,
        /** Inclusive line range for navigation. */
        val lineRange: Pair<Int, Int>? = null,
        /** Path relative to the project root, when available. */
        val relativePath: String? = null,
    ) {
        fun addChild(child: DependencyNode) {
            children.add(child)
        }

        fun getDisplayText(): String = buildString {
            append(relativePath ?: name)
            if (details != null && type != NodeType.GRAMMAR_FILE && type != NodeType.JAVA_FILE) {
                append(" ($details)")
            }
            if (
                reachability != null &&
                    reachability != "main" &&
                    type != NodeType.EMBEDDED_CODE &&
                    type != NodeType.UNRESOLVED_IMPORT
            ) {
                append(" ← $reachability")
            }
        }

        fun getIcon(): Icon = type.icon
    }

    /** Places dependencies under the main grammar, with embedded code nested under its owning file. */
    fun buildTree(
        graph: UnifiedDependencyResolver.DependencyGraph,
        projectRoot: File? = null,
    ): DependencyNode {
        val mainGrammar =
            graph.grammarFiles.find {
                it.file.absolutePath == graph.mainGrammar.absolutePath
            }

        val mainRelPath = graph.mainGrammar.displayPath(projectRoot)

        val root =
            DependencyNode(
                type = NodeType.GRAMMAR_FILE,
                name = graph.mainGrammar.name,
                file = graph.mainGrammar,
                relativePath = mainRelPath,
                reachability = "main",
            )

        if (mainGrammar != null && mainGrammar.hasEmbeddedCode) {
            for (code in mainGrammar.embeddedCode.sortedBy { it.startLine }) {
                root.addChild(createEmbeddedCodeNode(code))
            }
        }

        val importedGrammars =
            graph.grammarFiles.filter {
                it.file.absolutePath != graph.mainGrammar.absolutePath
            }
        for (imported in importedGrammars) {
            root.addChild(createGrammarNode(imported, projectRoot))
        }

        for ((file, referencedBy) in graph.javaFiles) {
            val refFileName = File(referencedBy).name
            val nodeType = if (file.extension == "kt") NodeType.KOTLIN_FILE else NodeType.JAVA_FILE
            val importLabel = if (file.extension == "kt") "Kotlin import" else "Java import"
            root.addChild(
                DependencyNode(
                    type = nodeType,
                    name = file.name,
                    file = file,
                    relativePath = file.displayPath(projectRoot),
                    reachability = "$importLabel from $refFileName",
                ),
            )
        }

        for (unresolved in graph.unresolvedGrammarImports) {
            root.addChild(
                DependencyNode(
                    type = NodeType.UNRESOLVED_IMPORT,
                    name = unresolved,
                    details = "unresolved grammar",
                ),
            )
        }

        for ((importStatement, _, reason) in graph.unresolvedJavaImports) {
            root.addChild(
                DependencyNode(
                    type = NodeType.UNRESOLVED_IMPORT,
                    name = importStatement,
                    details = reason,
                ),
            )
        }

        return root
    }

    private fun File.displayPath(projectRoot: File?): String =
        if (projectRoot != null) {
            try {
                projectRoot.toPath().relativize(toPath()).toString().replace('\\', '/')
            } catch (e: Exception) {
                name
            }
        } else {
            name
        }

    private fun createGrammarNode(
        grammarNode: UnifiedDependencyResolver.GrammarNode,
        projectRoot: File?,
    ): DependencyNode {
        val relativePath = grammarNode.file.displayPath(projectRoot)

        val node =
            DependencyNode(
                type = NodeType.GRAMMAR_FILE,
                name = grammarNode.file.name,
                file = grammarNode.file,
                relativePath = relativePath,
                reachability = grammarNode.reachability,
            )

        if (grammarNode.hasEmbeddedCode) {
            for (code in grammarNode.embeddedCode.sortedBy { it.startLine }) {
                node.addChild(createEmbeddedCodeNode(code))
            }
        }

        return node
    }

    private fun createEmbeddedCodeNode(code: EmbeddedCode): DependencyNode =
        DependencyNode(
            type = NodeType.EMBEDDED_CODE,
            name = code.type.displayName,
            details = "line ${code.startLine}",
            codeContent = code.content,
            lineRange = code.startLine to code.endLine,
        )

    data class GraphStats(
        val totalGrammarFiles: Int,
        val grammarsWithCode: Int,
        val totalEmbeddedBlocks: Int,
        val sourceFiles: Int,
        val unresolvedImports: Int,
    )

    fun computeStats(graph: UnifiedDependencyResolver.DependencyGraph): GraphStats {
        val allEmbedded = graph.grammarFiles.flatMap { it.embeddedCode }

        return GraphStats(
            totalGrammarFiles = graph.grammarFiles.size,
            grammarsWithCode = graph.grammarFiles.count { it.hasEmbeddedCode },
            totalEmbeddedBlocks = allEmbedded.size,
            sourceFiles = graph.javaFiles.size,
            unresolvedImports = graph.unresolvedGrammarImports.size + graph.unresolvedJavaImports.size,
        )
    }
}
