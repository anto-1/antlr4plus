@file:Suppress("MISSING_DEPENDENCY_IN_INFERRED_TYPE_ANNOTATION_WARNING")

package com.antlr4plus.parsing

import com.antlr4plus.filebinding.FileBindingService
import com.antlr4plus.parsing.CompiledParsingService.ClassFileSearchResult
import com.antlr4plus.parsing.CompiledParsingService.DependencyContext
import com.antlr4plus.parsing.CompiledParsingService.PreResolveResult
import com.antlr4plus.parsing.CompiledParsingService.SupportFilesResult
import com.antlr4plus.parsing.CompiledParsingService.getClassValuedOptions
import com.antlr4plus.parsing.CompiledParsingService.getContextSuperClass
import com.antlr4plus.parsing.CompiledParsingService.getSuperClass
import com.antlr4plus.parsing.CompiledParsingService.getTokenLabelType
import com.antlr4plus.previewService.PreviewGeneratedArtifactPolicy
import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.LexerGrammar

/** Collects generated sources and resolves grammar-referenced support files for compilation. */
internal class CompiledSourceCollector(
    private val dependencyResolver: CompiledDependencyResolver,
    private val log: Logger,
) {
    /** Collects generated sources, class-valued option targets and their discovered references. */
    suspend fun collectSourceFiles(
        generatedCodeDir: File,
        grammarDir: File,
        projectBasePath: File?,
        g: Grammar,
        lg: LexerGrammar,
        indicator: ProgressIndicator?,
        previewService: PreviewService? = null,
        previewContext: CompiledParsingService.PreviewCompilationContext? = null,
    ): TrustedFilesManager.SourceFileCollection {
        val generatedFiles = mutableListOf<File>()
        val baseClassFiles = mutableListOf<File>()
        val missingSuperClasses = mutableListOf<String>()

        val scopeFolder =
            previewContext?.inputFile?.parent?.let { File(it.path) }
                ?: previewService?.state?.g4pInputFile?.parent?.let { File(it.path) }
                ?: grammarDir

        val boundGrammarPath =
            previewContext?.boundGrammarPath
                ?: previewService?.let { ps ->
                    val g4pFile = ps.state.g4pInputFile ?: return@let null
                    ps.project.service<FileBindingService>().getBoundGrammarPath(g4pFile)
                }

        val foundJavaFiles = RuntimeCompiler.findJavaFiles(generatedCodeDir)
        val foundKotlinFiles = KotlinRuntimeCompiler.findKotlinFiles(generatedCodeDir)
        val foundGenFiles =
            (previewContext?.let { context ->
                val parserGrammarFile = File(context.boundGrammarPath)
                val sourceExtension = if ((foundKotlinFiles.isNotEmpty() && foundJavaFiles.isEmpty())) "kt" else "java"
                val plan =
                    PreviewGeneratedArtifactPolicy.buildPlan(
                        parserGrammarFile = parserGrammarFile,
                        lexerGrammarFile = context.lexerGrammarFile,
                        charset = context.grammarCharset,
                        sourceExtension = sourceExtension,
                        generateListener = context.generateListener,
                        generateVisitor = context.generateVisitor,
                    )
                (foundJavaFiles + foundKotlinFiles).filter { it.name in plan.requiredSourceNames }
            }
                ?: previewService?.let { service ->
                    val parserGrammarFile = service.state.grammarFile?.let { File(it.path) }
                    if (parserGrammarFile == null) {
                        foundJavaFiles + foundKotlinFiles
                    } else {
                        val lexerGrammarFile = service.state.lexerGrammarFile?.let { File(it.path) }
                        val charset =
                            service.state.liveSettings
                                ?.encoding
                                ?.takeIf { it.isNotBlank() }
                                ?.let(java.nio.charset.Charset::forName) ?: Charsets.UTF_8
                        val sourceExtension =
                            if ((foundKotlinFiles.isNotEmpty() && foundJavaFiles.isEmpty())) "kt" else "java"
                        val plan =
                            PreviewGeneratedArtifactPolicy.buildPlan(
                                parserGrammarFile = parserGrammarFile,
                                lexerGrammarFile = lexerGrammarFile,
                                charset = charset,
                                sourceExtension = sourceExtension,
                                generateListener = service.state.liveSettings?.generateListener != false,
                                generateVisitor = service.state.liveSettings?.generateVisitor == true,
                            )
                        (foundJavaFiles + foundKotlinFiles).filter {
                            it.name in plan.requiredSourceNames
                        }
                    }
                }) ?: (foundJavaFiles + foundKotlinFiles)
        log.info(
            "Found ${foundJavaFiles.size} Java and ${foundKotlinFiles.size} Kotlin files in $generatedCodeDir",
        )
        if (foundGenFiles.size != foundJavaFiles.size + foundKotlinFiles.size) {
            log.info(
                "Excluded ${foundJavaFiles.size + foundKotlinFiles.size - foundGenFiles.size} " +
                    "source file(s) not owned by the active preview grammar",
            )
        }
        generatedFiles.addAll(foundGenFiles)

        // Resolve source files for all three class-valued grammar options.
        val lexerOptions = getClassValuedOptions(lg)
        val parserOptions = getClassValuedOptions(g)

        val classNamesToFind = (lexerOptions.values + parserOptions.values).map { it.substringAfterLast('.') }.toSet()

        val optionLabels: Map<String, String> = buildMap {
            for ((opt, raw) in lexerOptions) put(raw.substringAfterLast('.'), "lexer $opt = $raw")
            for ((opt, raw) in parserOptions) {
                val simple = raw.substringAfterLast('.')
                merge(simple, "parser $opt = $raw") { a, b -> "$a; $b" }
            }
        }

        if (classNamesToFind.isNotEmpty()) {
            indicator?.text = "Searching for base class / context / token-label files..."
            val displayName =
                parserOptions["superClass"]
                    ?: lexerOptions["superClass"]
                    ?: parserOptions["contextSuperClass"]
                    ?: parserOptions["TokenLabelType"]
            val superClassContext =
                DependencyContext(
                    generatedClass = g.name,
                    superClass = displayName,
                )
            when (
                val searchResult =
                    dependencyResolver.findClassFiles(
                        classNamesToFind,
                        grammarDir,
                        projectBasePath,
                        indicator,
                        superClassContext,
                        previewService,
                        scopeFolder,
                        boundGrammarPath,
                    )
            ) {
                is ClassFileSearchResult.UserCancelled -> {
                    return TrustedFilesManager.SourceFileCollection(
                        generatedFiles = emptyList(),
                        baseClassFiles = emptyList(),
                        userCancelledClassName = searchResult.className,
                    )
                }
                is ClassFileSearchResult.Success -> {
                    for ((className, file) in searchResult.files) {
                        baseClassFiles.add(file)
                        log.info(
                            "Found ${optionLabels[className] ?: className}: ${file.absolutePath}",
                        )
                    }
                    for (simpleName in classNamesToFind) {
                        if (!searchResult.files.containsKey(simpleName)) {
                            val label = optionLabels[simpleName] ?: simpleName
                            missingSuperClasses.add(label)
                            log.warn(
                                "$label specified but $simpleName.java/.kt not found in project",
                            )
                        }
                    }
                }
            }
        }

        val analyzedClasses = mutableSetOf<String>()
        val currentBaseFiles = baseClassFiles.toList()
        for (baseFile in currentBaseFiles) {
            indicator?.text = "Analyzing ${baseFile.name} for dependencies..."
            val referencedClasses = GrammarActionAnalyzer.analyzeSourceFile(baseFile)
            analyzedClasses.addAll(referencedClasses)
        }

        val generatedClassNames = generatedFiles.map { it.nameWithoutExtension }.toSet()
        analyzedClasses.removeAll(generatedClassNames)

        if (analyzedClasses.isNotEmpty()) {
            log.info("Base class files reference external classes: $analyzedClasses")
            indicator?.text = "Searching for base class dependencies..."

            val referencingBaseClass = currentBaseFiles.firstOrNull()?.nameWithoutExtension
            val depContext =
                DependencyContext(
                    generatedClass = g.name,
                    superClass =
                        parserOptions["superClass"]
                            ?: lexerOptions["superClass"]
                            ?: parserOptions["contextSuperClass"]
                            ?: parserOptions["TokenLabelType"],
                    referencingClass = referencingBaseClass,
                )

            when (
                val searchResult =
                    dependencyResolver.findClassFiles(
                        analyzedClasses,
                        grammarDir,
                        projectBasePath,
                        indicator,
                        depContext,
                        previewService,
                        scopeFolder,
                        boundGrammarPath,
                    )
            ) {
                is ClassFileSearchResult.UserCancelled -> {
                    return TrustedFilesManager.SourceFileCollection(
                        generatedFiles = emptyList(),
                        baseClassFiles = emptyList(),
                        userCancelledClassName = searchResult.className,
                    )
                }
                is ClassFileSearchResult.Success -> {
                    for ((className, file) in searchResult.files) {
                        val classFileName = file.nameWithoutExtension
                        if (
                            !baseClassFiles.any { it.nameWithoutExtension == classFileName } &&
                                !generatedFiles.any { it.nameWithoutExtension == classFileName }
                        ) {
                            log.info("Adding dependency $className from ${file.absolutePath}")
                            baseClassFiles.add(file)
                        }
                    }
                }
            }
        }

        return TrustedFilesManager.SourceFileCollection(
            generatedFiles = generatedFiles.distinctBy { it.absolutePath },
            baseClassFiles = baseClassFiles.distinctBy { it.nameWithoutExtension },
            missingSuperClasses = missingSuperClasses,
        )
    }

    suspend fun findSupportFiles(
        grammarDir: File,
        projectBasePath: File?,
        indicator: ProgressIndicator?,
        grammarName: String? = null,
        previewService: PreviewService? = null,
        previewContext: CompiledParsingService.PreviewCompilationContext? = null,
    ): SupportFilesResult {
        indicator?.text = "Analyzing grammar for external references..."

        val scopeFolder =
            previewContext?.inputFile?.parent?.let { File(it.path) }
                ?: previewService?.state?.g4pInputFile?.parent?.let { File(it.path) }
                ?: grammarDir

        val boundGrammarPath =
            previewContext?.boundGrammarPath
                ?: previewService?.let { ps ->
                    val g4pFile = ps.state.g4pInputFile ?: return@let null
                    ps.project.service<FileBindingService>().getBoundGrammarPath(g4pFile)
                }

        val grammarFiles = grammarDir.listFiles { f -> f.extension == "g4" } ?: emptyArray()
        val allReferencedClasses = mutableSetOf<String>()
        val sourceGrammarFiles = mutableSetOf<String>()
        val grammarCharset =
            previewContext?.grammarCharset
                ?: previewService
                    ?.state
                    ?.liveSettings
                    ?.encoding
                    ?.takeIf { it.isNotBlank() }
                    ?.let { encoding ->
                        runCatching { java.nio.charset.Charset.forName(encoding) }.getOrNull()
                    }
                ?: Charsets.UTF_8

        for (grammarFile in grammarFiles) {
            log.info("Analyzing grammar file: ${grammarFile.name}")
            val analysis = GrammarActionAnalyzer.analyzeGrammarFile(grammarFile, grammarCharset)
            if (analysis.referencedClasses.isNotEmpty()) {
                allReferencedClasses.addAll(analysis.referencedClasses)
                sourceGrammarFiles.add(analysis.sourceGrammarName)
            }
        }

        if (allReferencedClasses.isEmpty()) {
            log.info("No external class references found in grammar")
            return SupportFilesResult(emptyList(), emptySet(), emptySet(), emptySet())
        }

        log.info(
            "Grammar references external classes: $allReferencedClasses (from: $sourceGrammarFiles)",
        )
        indicator?.text = "Searching for referenced classes: ${allReferencedClasses.joinToString(", ")}"

        val sourceGrammarName = sourceGrammarFiles.firstOrNull()?.removeSuffix(".g4")
        val depContext =
            if (grammarName != null || sourceGrammarName != null) {
                DependencyContext(
                    generatedClass = grammarName ?: sourceGrammarName ?: "Grammar",
                    superClass = null,
                    referencingClass = sourceGrammarName,
                )
            } else null

        return when (
            val searchResult =
                dependencyResolver.findClassFiles(
                    allReferencedClasses,
                    grammarDir,
                    projectBasePath,
                    indicator,
                    depContext,
                    previewService,
                    scopeFolder,
                    boundGrammarPath,
                )
        ) {
            is ClassFileSearchResult.UserCancelled -> {
                SupportFilesResult(
                    foundFiles = emptyList(),
                    referencedClasses = allReferencedClasses,
                    missingClasses = allReferencedClasses,
                    sourceGrammarFiles = sourceGrammarFiles,
                    userCancelledClassName = searchResult.className,
                )
            }
            is ClassFileSearchResult.Success -> {
                val foundFiles = searchResult.files.values.toList()
                val missingClasses = allReferencedClasses - searchResult.files.keys

                log.info(
                    "Found ${foundFiles.size} of ${allReferencedClasses.size} referenced class files",
                )

                SupportFilesResult(
                    foundFiles,
                    allReferencedClasses,
                    missingClasses,
                    sourceGrammarFiles,
                )
            }
        }
    }

    /** Resolves Java/Kotlin source conflicts before compilation; null means cancellation. */
    suspend fun preResolveExternalJavaFiles(
        grammarFile: VirtualFile,
        lexerGrammar: LexerGrammar?,
        parserGrammar: Grammar?,
        project: Project,
        previewService: PreviewService,
        g4pFolder: File? = null,
    ): PreResolveResult? {
        val grammarDir = File(grammarFile.path).parentFile
        val scopeFolder = g4pFolder ?: grammarDir
        val projectBasePath = project.basePath?.let { File(it) }
        val activeInputFile = previewService.state.g4pInputFile
        val grammarCharset =
            previewService.state.liveSettings
                ?.encoding
                ?.takeIf { it.isNotBlank() }
                ?.let { encoding ->
                    runCatching { java.nio.charset.Charset.forName(encoding) }.getOrNull()
                } ?: Charsets.UTF_8

        val boundGrammarPath = activeInputFile?.let { g4pFile ->
            project.service<FileBindingService>().getBoundGrammarPath(g4pFile)
        }

        val externalSourceFiles = mutableListOf<File>()
        var hadConflictResolution = false

        // Returns false when a dependency choice is cancelled.
        suspend fun resolveTransitiveSources(
            initialFiles: Collection<File>,
            cancellationContext: String,
            contextForFile: (File) -> DependencyContext,
        ): Boolean {
            val pendingFiles = ArrayDeque(initialFiles.toList())
            val processedFiles = mutableSetOf<String>()

            while (pendingFiles.isNotEmpty()) {
                val currentFile = pendingFiles.removeFirst()
                val currentFilePath = currentFile.absolutePath

                if (currentFilePath in processedFiles) continue
                processedFiles.add(currentFilePath)

                previewService.publishStatus("Analyzing ${currentFile.name}...")
                val referencedClasses = GrammarActionAnalyzer.analyzeSourceFile(currentFile)
                if (referencedClasses.isEmpty()) continue

                val depContext = contextForFile(currentFile)
                when (
                    val depResult =
                        dependencyResolver.findClassFiles(
                            referencedClasses,
                            grammarDir,
                            projectBasePath,
                            indicator = null,
                            depContext,
                            previewService,
                            scopeFolder,
                            boundGrammarPath,
                        )
                ) {
                    is ClassFileSearchResult.UserCancelled -> {
                        log.info("User cancelled $cancellationContext for ${depResult.className}")
                        dependencyResolver.clearResolvedClasses()
                        return false
                    }
                    is ClassFileSearchResult.Success -> {
                        for (file in depResult.files.values) {
                            val filePath = file.absolutePath
                            if (!externalSourceFiles.any { it.absolutePath == filePath }) {
                                externalSourceFiles.add(file)
                                if (filePath !in processedFiles) {
                                    pendingFiles.addLast(file)
                                }
                            }
                        }
                        if (depResult.hadConflictDialog) hadConflictResolution = true
                    }
                }
            }
            return true
        }

        val superClassNames = mutableSetOf<String>()
        superClassNames.addAll(getClassValuedOptionSimpleNames(lexerGrammar))
        superClassNames.addAll(getClassValuedOptionSimpleNames(parserGrammar))

        if (superClassNames.isNotEmpty()) {
            previewService.publishStatus("Resolving dependencies...")
            val depContext =
                DependencyContext(
                    generatedClass = parserGrammar?.name ?: "Grammar",
                    superClass =
                        getSuperClass(parserGrammar)
                            ?: getSuperClass(lexerGrammar)
                            ?: getContextSuperClass(parserGrammar)
                            ?: getTokenLabelType(parserGrammar),
                )

            when (
                val searchResult =
                    dependencyResolver.findClassFiles(
                        superClassNames,
                        grammarDir,
                        projectBasePath,
                        indicator = null,
                        depContext,
                        previewService,
                        scopeFolder,
                        boundGrammarPath,
                    )
            ) {
                is ClassFileSearchResult.UserCancelled -> {
                    log.info("User cancelled dependency resolution for ${searchResult.className}")
                    previewService.appendAntlrLine(
                        "Preview cancelled: Multiple files found for class '${searchResult.className}'. " +
                            "Please resolve the conflict or remove duplicate class files.",
                    )
                    dependencyResolver.clearResolvedClasses()
                    return null
                }
                is ClassFileSearchResult.Success -> {
                    externalSourceFiles.addAll(searchResult.files.values)
                    if (searchResult.hadConflictDialog) hadConflictResolution = true

                    val completed =
                        resolveTransitiveSources(
                            searchResult.files.values,
                            cancellationContext = "dependency resolution",
                        ) { currentFile ->
                            DependencyContext(
                                generatedClass = parserGrammar?.name ?: "Grammar",
                                superClass =
                                    getSuperClass(parserGrammar)
                                        ?: getSuperClass(lexerGrammar)
                                        ?: getContextSuperClass(parserGrammar)
                                        ?: getTokenLabelType(parserGrammar),
                                referencingClass = currentFile.nameWithoutExtension,
                            )
                        }
                    if (!completed) return null
                }
            }
        }

        val grammarFiles = grammarDir.listFiles { f -> f.extension == "g4" } ?: emptyArray()
        val allReferencedClasses = mutableSetOf<String>()
        for (gf in grammarFiles) {
            val analysis = GrammarActionAnalyzer.analyzeGrammarFile(gf, grammarCharset)
            allReferencedClasses.addAll(analysis.referencedClasses)
        }

        if (allReferencedClasses.isNotEmpty()) {
            previewService.publishStatus("Searching for support files...")
            val supportContext =
                DependencyContext(
                    generatedClass = parserGrammar?.name ?: "Grammar",
                    superClass = null,
                    referencingClass = grammarFiles.firstOrNull()?.nameWithoutExtension,
                )
            when (
                val supportResult =
                    dependencyResolver.findClassFiles(
                        allReferencedClasses,
                        grammarDir,
                        projectBasePath,
                        indicator = null,
                        supportContext,
                        previewService,
                        scopeFolder,
                        boundGrammarPath,
                    )
            ) {
                is ClassFileSearchResult.UserCancelled -> {
                    log.info(
                        "User cancelled support file resolution for ${supportResult.className}",
                    )
                    dependencyResolver.clearResolvedClasses()
                    return null
                }
                is ClassFileSearchResult.Success -> {
                    for (file in supportResult.files.values) {
                        if (!externalSourceFiles.any { it.absolutePath == file.absolutePath }) {
                            externalSourceFiles.add(file)
                        }
                    }
                    if (supportResult.hadConflictDialog) hadConflictResolution = true

                    val completed =
                        resolveTransitiveSources(
                            supportResult.files.values,
                            cancellationContext = "transitive resolution",
                        ) { currentFile ->
                            DependencyContext(
                                generatedClass = parserGrammar?.name ?: "Grammar",
                                superClass = null,
                                referencingClass = currentFile.nameWithoutExtension,
                            )
                        }
                    if (!completed) return null
                }
            }
        }

        log.info(
            "Pre-resolved ${externalSourceFiles.size} external Java files (hadConflict=$hadConflictResolution)",
        )
        dependencyResolver.clearResolvedClasses()
        return PreResolveResult(externalSourceFiles, hadConflictResolution)
    }

    /** Reduces qualified option values to the simple filenames used by the resolver. */
    private fun getClassValuedOptionSimpleNames(grammar: Grammar?): Set<String> =
        getClassValuedOptions(grammar).values.map { it.substringAfterLast('.') }.toSet()
}
