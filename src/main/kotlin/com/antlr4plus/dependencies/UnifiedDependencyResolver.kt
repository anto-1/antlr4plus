package com.antlr4plus.dependencies

import com.antlr4plus.parsing.embedded.EmbeddedCode
import com.antlr4plus.parsing.embedded.GrammarCodeExtractor
import com.antlr4plus.util.GeneratedWorkspaceFileOps
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.nio.charset.Charset
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Resolves grammar imports and discovered Java/Kotlin source imports for the dependency view. */
object UnifiedDependencyResolver {

    private val LOG = Logger.getInstance(UnifiedDependencyResolver::class.java)

    // JDK packages do not need source-file dependency resolution.
    private val JDK_PACKAGE_PREFIXES =
        setOf(
            "java.",
            "javax.",
            "sun.",
            "com.sun.",
            "jdk.",
            "org.w3c.",
            "org.xml.",
            "org.ietf.",
        )

    private val ANTLR_RUNTIME_PACKAGES =
        setOf(
            "org.antlr.v4.runtime",
            "org.antlr.runtime",
            "org.antlr.v4",
            "org.antlr",
        )

    private fun isAntlrRuntime(importPath: String): Boolean = ANTLR_RUNTIME_PACKAGES.any { pkg ->
        importPath == pkg || importPath.startsWith("$pkg.")
    }

    private val SKIP_DIRECTORIES =
        setOf(
            ".git",
            ".gradle",
            ".idea",
            "build",
            "out",
            "target",
            "node_modules",
            ".svn",
            ".hg",
            "bin",
            ".settings",
        )

    data class DependencyGraph(
        val mainGrammar: File,
        val grammarFiles: List<GrammarNode>,

        /** Resolved Java and Kotlin support sources. */
        val javaFiles: List<JavaNode>,
        val unresolvedGrammarImports: List<String>,
        val unresolvedJavaImports: List<UnresolvedJavaImport>,
    )

    data class GrammarNode(
        val file: File,
        val embeddedCode: List<EmbeddedCode>,
        val reachability: String,
    ) {
        val hasEmbeddedCode: Boolean
            get() = embeddedCode.isNotEmpty()
    }

    data class JavaNode(
        val file: File,
        /** Path of the grammar or source file that referenced this dependency. */
        val referencedBy: String,
    )

    data class UnresolvedJavaImport(
        val importStatement: String,
        val referencedBy: String,
        val reason: String,
    )

    fun resolve(
        grammarFile: File,
        project: Project,
        libDirs: List<File> = emptyList(),
        grammarCharset: Charset = Charsets.UTF_8,
    ): DependencyGraph {
        LOG.info("Resolving complete dependency graph for: ${grammarFile.name}")

        val grammarNodes = mutableListOf<GrammarNode>()
        val javaNodes = mutableListOf<JavaNode>()
        val unresolvedGrammarImports = mutableListOf<String>()
        val unresolvedJavaImports = mutableListOf<UnresolvedJavaImport>()
        val superClassRefs =
            mutableListOf<
                Pair<GrammarCodeExtractor.SuperClassRef, String>,
            >() // Reference paired with its declaring grammar path.

        val visitedGrammars = mutableSetOf<String>()
        val visitedJavaFiles = mutableSetOf<String>()

        resolveGrammarDependencies(
            grammarFile = grammarFile,
            reachability = "main",
            libDirs = libDirs,
            visited = visitedGrammars,
            results = grammarNodes,
            unresolvedImports = unresolvedGrammarImports,
            superClassRefs = superClassRefs,
            grammarCharset = grammarCharset,
        )

        LOG.info("Found ${grammarNodes.size} grammar files in dependency graph")
        if (superClassRefs.isNotEmpty()) {
            LOG.info("Found ${superClassRefs.size} class-valued option references:")
            superClassRefs.forEach { (ref, by) ->
                LOG.info("  - ${ref.option} = ${ref.className} (${ref.type}) from ${File(by).name}")
            }
        }

        val allJavaImports = mutableListOf<Pair<String, String>>() // Import paired with its referring file path.
        for (node in grammarNodes) {
            if (!node.hasEmbeddedCode) continue

            val imports = extractJavaImportsFromEmbeddedCode(node.embeddedCode)
            for (imp in imports) {
                allJavaImports.add(imp to node.file.absolutePath)
            }
        }

        // Class-valued grammar options also introduce source dependencies.
        for ((ref, referencedBy) in superClassRefs) {
            allJavaImports.add(ref.className to referencedBy)
        }

        LOG.info(
            "Found ${allJavaImports.size} source imports (including ${superClassRefs.size} superClass refs)",
        )

        val projectRoot = getProjectRoot(project, grammarFile)
        LOG.info(
            "Search boundary: ${grammarFile.parentFile?.name} -> ${projectRoot?.name ?: "none"}",
        )

        resolveJavaDependencies(
            imports = allJavaImports,
            grammarFile = grammarFile,
            projectRoot = projectRoot,
            visited = visitedJavaFiles,
            results = javaNodes,
            unresolvedImports = unresolvedJavaImports,
        )

        LOG.info(
            "Resolved ${javaNodes.size} source files (Java/Kotlin), ${unresolvedJavaImports.size} unresolved",
        )

        return DependencyGraph(
            mainGrammar = grammarFile,
            grammarFiles = grammarNodes,
            javaFiles = javaNodes,
            unresolvedGrammarImports = unresolvedGrammarImports,
            unresolvedJavaImports = unresolvedJavaImports,
        )
    }

    private fun resolveGrammarDependencies(
        grammarFile: File,
        reachability: String,
        libDirs: List<File>,
        visited: MutableSet<String>,
        results: MutableList<GrammarNode>,
        unresolvedImports: MutableList<String>,
        superClassRefs: MutableList<Pair<GrammarCodeExtractor.SuperClassRef, String>>,
        grammarCharset: Charset,
    ) {
        if (!grammarFile.exists()) return

        val canonicalPath = grammarFile.canonicalPath
        if (canonicalPath in visited) return
        visited.add(canonicalPath)

        val content = grammarFile.readText(grammarCharset)
        val embeddedCode =
            try {
                GrammarCodeExtractor.extract(content, grammarFile.absolutePath)
            } catch (e: Exception) {
                LOG.error("Error extracting grammar code: ${grammarFile.absolutePath}", e)
                emptyList()
            }

        results.add(
            GrammarNode(
                file = grammarFile,
                embeddedCode = embeddedCode,
                reachability = reachability,
            ),
        )

        val searchDirs = listOf(grammarFile.parentFile) + libDirs

        val superClasses = GrammarCodeExtractor.extractSuperClassOptions(content)
        for (superClass in superClasses) {
            superClassRefs.add(superClass to canonicalPath)
        }

        val imports = extractGrammarImports(content)
        for (importName in imports) {
            val importedFile = resolveGrammarFile(importName, searchDirs)
            if (importedFile == null) {
                unresolvedImports.add(importName)
                continue
            }
            resolveGrammarDependencies(
                grammarFile = importedFile,
                reachability = "import",
                libDirs = libDirs,
                visited = visited,
                results = results,
                unresolvedImports = unresolvedImports,
                superClassRefs = superClassRefs,
                grammarCharset = grammarCharset,
            )
        }

        val tokenVocab = extractTokenVocab(content)
        if (tokenVocab != null) {
            val vocabFile = resolveGrammarFile(tokenVocab, searchDirs)
            if (vocabFile == null) {
                unresolvedImports.add("tokenVocab=$tokenVocab")
            } else {
                resolveGrammarDependencies(
                    grammarFile = vocabFile,
                    reachability = "tokenVocab",
                    libDirs = libDirs,
                    visited = visited,
                    results = results,
                    unresolvedImports = unresolvedImports,
                    superClassRefs = superClassRefs,
                    grammarCharset = grammarCharset,
                )
            }
        }
    }

    private fun extractJavaImportsFromEmbeddedCode(embeddedCode: List<EmbeddedCode>): List<String> {
        val imports = mutableSetOf<String>()

        for (code in embeddedCode) {
            val extractedImports = parseSourceImports(code.content)
            imports.addAll(extractedImports)
        }

        return imports.filter { imp ->
            !JDK_PACKAGE_PREFIXES.any { prefix -> imp.startsWith(prefix) }
        }
    }

    /** Follows discovered imports through project source candidates, recording unresolved names. */
    private fun resolveJavaDependencies(
        imports: List<Pair<String, String>>,
        grammarFile: File,
        projectRoot: File?,
        visited: MutableSet<String>,
        results: MutableList<JavaNode>,
        unresolvedImports: MutableList<UnresolvedJavaImport>,
    ) {
        val pendingImports = imports.toMutableList()
        val resolvedCanonicalPaths = mutableSetOf<String>()
        val sourceCandidates by
            lazy(LazyThreadSafetyMode.NONE) {
                projectRoot?.let { findSourceCandidates(it.toPath()) }.orEmpty()
            }
        while (pendingImports.isNotEmpty()) {
            val (importPath, referencedBy) = pendingImports.removeAt(0)

            if (importPath in visited) continue
            visited.add(importPath)

            val sourceFiles =
                if (importPath.endsWith(".*")) {
                    findSourceFilesByPackage(
                        packageName = importPath.removeSuffix(".*"),
                        startDir = grammarFile.parentFile,
                        boundaryRoot = projectRoot,
                        candidates = sourceCandidates,
                    )
                } else {
                    val simpleName = importPath.substringAfterLast('.')
                    listOfNotNull(
                        findNearestSourceFile(
                            className = simpleName,
                            qualifiedName = if (importPath.contains('.')) importPath else null,
                            startDir = grammarFile.parentFile,
                            boundaryRoot = projectRoot,
                            candidates = sourceCandidates,
                        ),
                    )
                }

            if (sourceFiles.isEmpty()) {
                when {
                    isAntlrRuntime(importPath) -> {
                        LOG.debug("Skipping bundled ANTLR runtime import: $importPath")
                        continue
                    }
                    isLikelyJarDependency(importPath) -> {
                        unresolvedImports.add(
                            UnresolvedJavaImport(
                                importPath,
                                referencedBy,
                                "JAR dependency (not source)",
                            ),
                        )
                        continue
                    }
                    else -> {
                        val reason =
                            if (importPath.contains('.')) {
                                "Source file not found"
                            } else {
                                "Class '$importPath' not found"
                            }
                        unresolvedImports.add(
                            UnresolvedJavaImport(importPath, referencedBy, reason),
                        )
                        continue
                    }
                }
            }

            for (sourceFile in sourceFiles) {
                // Keep local source matches even when their package resembles a bundled framework.
                val canonicalPath = sourceFile.canonicalPath

                if (!resolvedCanonicalPaths.add(canonicalPath)) {
                    continue
                }

                val content =
                    try {
                        readSourceFileWithVfsRefresh(sourceFile)
                    } catch (e: Exception) {
                        LOG.warn("Failed to read source file: ${sourceFile.path}", e)
                        unresolvedImports.add(
                            UnresolvedJavaImport(importPath, referencedBy, "Failed to read file"),
                        )
                        continue
                    }

                val fileImports =
                    parseSourceImports(content).filter { imp ->
                        !JDK_PACKAGE_PREFIXES.any { prefix -> imp.startsWith(prefix) }
                    }

                results.add(
                    JavaNode(
                        file = sourceFile,
                        referencedBy = referencedBy,
                    ),
                )

                LOG.debug("Resolved source dependency: ${sourceFile.name} via '$importPath'")

                for (transitiveImport in fileImports) {
                    if (transitiveImport !in visited) {
                        pendingImports.add(transitiveImport to canonicalPath)
                    }
                }
            }
        }
    }

    /** Walks the project boundary without following links, pruning build and vendor directories. */
    private fun findSourceCandidates(root: Path): List<File> {
        if (!GeneratedWorkspaceFileOps.isDirectory(root)) return emptyList()
        return runCatching {
                val candidates = mutableListOf<File>()
                Files.walkFileTree(
                    root,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            dir: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            if (dir != root && dir.fileName?.toString() in SKIP_DIRECTORIES) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            val filename = file.fileName.toString()
                            val hasSourceExtension = filename.endsWith(".java") || filename.endsWith(".kt")
                            if (
                                attrs.isRegularFile &&
                                    hasSourceExtension &&
                                    GeneratedWorkspaceFileOps.isRegularFile(file)
                            ) {
                                candidates.add(file.toFile())
                            }
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
                candidates
            }
            .onFailure { failure ->
                LOG.warn("Failed to search source boundary $root", failure)
            }
            .getOrDefault(emptyList())
    }

    private fun getProjectRoot(project: Project, grammarFile: File): File? {
        try {
            val projectRootManager = ProjectRootManager.getInstance(project)
            val contentRoots = projectRootManager.contentRoots
            // Only use a content root that contains this grammar.
            for (root in contentRoots) {
                val rootFile = File(root.path)
                if (isWithinBoundary(rootFile, grammarFile)) {
                    return rootFile
                }
            }
        } catch (e: Exception) {
            LOG.warn("Could not get project root from IntelliJ: ${e.message}")
        }

        project.basePath?.let { basePath ->
            val base = File(basePath)
            if (isWithinBoundary(base, grammarFile)) return base
        }

        var current = grammarFile.parentFile
        while (current != null) {
            if (
                File(current, ".git").exists() ||
                    File(current, "build.gradle").exists() ||
                    File(current, "build.gradle.kts").exists() ||
                    File(current, "pom.xml").exists() ||
                    File(current, ".idea").exists()
            ) {
                return current
            }
            current = current.parentFile
        }

        // Without a project boundary, limit candidates to the grammar directory.
        return grammarFile.parentFile
    }

    /** Path-component containment; /project-copy is not inside /project. */
    internal fun isWithinBoundary(root: File, candidate: File): Boolean =
        try {
            candidate.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
        } catch (_: Exception) {
            false
        }

    /** Prefers current document text, then VFS bytes, then disk; does not force a VFS refresh. */
    private fun readSourceFileWithVfsRefresh(file: File): String = runReadAction {
        val vf = LocalFileSystem.getInstance().findFileByIoFile(file)
        if (vf != null && vf.isValid) {
            val document = FileDocumentManager.getInstance().getDocument(vf)
            if (document != null) {
                return@runReadAction document.text
            }
            return@runReadAction String(vf.contentsToByteArray(), Charsets.UTF_8)
        }
        file.readText()
    }

    private fun isLikelyJarDependency(importPath: String): Boolean =
        setOf(
                "org.apache.",
                "org.springframework.",
                "com.google.",
                "com.fasterxml.",
                "org.slf4j.",
                "org.junit.",
                "org.mockito.",
                "io.netty.",
                "com.amazonaws.",
                "org.hibernate.",
                "jakarta.",
                "kotlin.",
                "kotlinx.",
                "org.jetbrains.",
            )
            .any { importPath.startsWith(it) }
}
