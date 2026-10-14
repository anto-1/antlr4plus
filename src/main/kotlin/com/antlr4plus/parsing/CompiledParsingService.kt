@file:Suppress("MISSING_DEPENDENCY_IN_INFERRED_TYPE_ANNOTATION_WARNING")

package com.antlr4plus.parsing

import com.antlr4plus.compat.compatIsTrusted
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.util.GeneratedWorkspaceFileOps
import com.antlr4plus.util.rethrowFatalOrCancellation
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.LexerGrammar
import org.jetbrains.annotations.TestOnly

/** Compiles and loads generated Java or Kotlin parsers for trusted projects. */
object CompiledParsingService {
    private val LOG = Logger.getInstance(CompiledParsingService::class.java)

    private const val MAX_CONCURRENT_PARSE_WORKERS = 4
    private const val MAX_PENDING_PARSE_TASKS = 32
    private const val PARSE_WORKER_IDLE_TIMEOUT_SECONDS = 30L

    /** Bounded daemon workers prevent timed-out parses from creating unlimited threads. */
    private val parseExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent
            .ThreadPoolExecutor(
                MAX_CONCURRENT_PARSE_WORKERS,
                MAX_CONCURRENT_PARSE_WORKERS,
                PARSE_WORKER_IDLE_TIMEOUT_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS,
                java.util.concurrent.ArrayBlockingQueue(MAX_PENDING_PARSE_TASKS),
                { runnable ->
                    Thread(runnable, "ANTLR4Plus-CompiledParser").apply {
                        isDaemon = true
                        priority = Thread.NORM_PRIORITY - 1
                    }
                },
                java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
            )
            .apply { allowCoreThreadTimeOut(true) }

    private val parserExecution = CompiledParserExecution(parseExecutor, LOG)

    private val classLoaderCache = ConcurrentHashMap<String, CompiledClassLoaderEntry>()
    private val cacheLifecycleLock = Any()
    private val cacheEpochs = mutableMapOf<String, Long>()

    private const val CACHE_KEY_SEPARATOR = '\u0000'

    private fun compilationCacheKey(grammarPath: String, generatedCodeDir: File): String {
        val workspacePath =
            try {
                generatedCodeDir.canonicalPath
            } catch (_: Exception) {
                generatedCodeDir.absoluteFile.normalize().path
            }
        return "$grammarPath$CACHE_KEY_SEPARATOR$workspacePath"
    }

    private fun grammarPathFromCacheKey(cacheKey: String): String = cacheKey.substringBefore(CACHE_KEY_SEPARATOR)

    private fun cacheEpoch(grammarPath: String): Long =
        synchronized(cacheLifecycleLock) { cacheEpochs[grammarPath] ?: 0L }

    /** Serializes compilation and loading per grammar to avoid partially written class files. */
    private class GrammarMutexLease {
        val mutex = Mutex()
        val users = AtomicInteger()
    }

    private val grammarMutexes = ConcurrentHashMap<String, GrammarMutexLease>()

    private fun acquireGrammarMutex(grammarPath: String): GrammarMutexLease =
        synchronized(cacheLifecycleLock) {
            requireNotNull(
                grammarMutexes.compute(grammarPath) { _, current ->
                    (current ?: GrammarMutexLease()).also { it.users.incrementAndGet() }
                },
            )
        }

    private fun releaseGrammarMutex(grammarPath: String, lease: GrammarMutexLease) {
        synchronized(cacheLifecycleLock) {
            grammarMutexes.computeIfPresent(grammarPath) { _, current ->
                if (current !== lease) {
                    current
                } else {
                    val remaining = current.users.decrementAndGet()
                    check(remaining >= 0) {
                        "Unbalanced preview serialization lease for $grammarPath"
                    }
                    current.takeIf { remaining > 0 }
                }
            }
        }
    }

    @TestOnly
    internal fun hasGrammarMutexForTest(grammarPath: String): Boolean = grammarMutexes.containsKey(grammarPath)

    data class PreResolveResult(
        val files: List<File>,
        val hadConflictResolution: Boolean,
    )

    private val dependencyResolver = CompiledDependencyResolver(LOG)
    private val sourceCollector = CompiledSourceCollector(dependencyResolver, LOG)

    sealed class ClassFileSearchResult {
        data class Success(
            val files: Map<String, File>,
            val hadConflictDialog: Boolean = false,
        ) : ClassFileSearchResult()

        data class UserCancelled(val className: String) : ClassFileSearchResult()
    }

    data class DependencyContext(
        val generatedClass: String,
        val superClass: String?,
        /** Base class that references a transitive dependency. */
        val referencingClass: String? = null,
    )

    /** Checks superClass, contextSuperClass and TokenLabelType for source dependencies. */
    fun hasSuperClass(grammar: Grammar?): Boolean = grammar != null && getClassValuedOptions(grammar).isNotEmpty()

    fun getSuperClass(grammar: Grammar?): String? = grammar?.getOptionString("superClass")

    /** Base type for generated rule contexts, independent of the parser superclass. */
    fun getContextSuperClass(grammar: Grammar?): String? = grammar?.getOptionString("contextSuperClass")

    /** Type of token labels such as t=ID. */
    fun getTokenLabelType(grammar: Grammar?): String? = grammar?.getOptionString("TokenLabelType")

    /** Maps class-valued grammar options to their possibly qualified names. */
    fun getClassValuedOptions(grammar: Grammar?): Map<String, String> {
        if (grammar == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        getSuperClass(grammar)?.let { result["superClass"] = it }
        getContextSuperClass(grammar)?.let { result["contextSuperClass"] = it }
        getTokenLabelType(grammar)?.let { result["TokenLabelType"] = it }
        return result
    }

    /**
     * Detects import-shaped lines naming the Java runtime, including subpackages. This regex also matches such lines
     * inside comments or multiline strings.
     */
    internal fun containsJavaAntlrRuntimeImport(kotlinSource: String): Boolean =
        Regex(
                pattern = """^\s*import\s+org\.antlr\.v4\.runtime(?:\.[A-Za-z_][A-Za-z0-9_]*)*(?:\.\*)?(?:\s|$)""",
                option = RegexOption.MULTILINE,
            )
            .containsMatchIn(kotlinSource)

    /** Compiled preview outcomes; failures must not fall back to interpreted execution. */
    sealed class CompiledParseResult {
        data class Success(val result: ParsingResult) : CompiledParseResult()

        data object GrammarCodeUntrusted : CompiledParseResult()

        data class MissingSuperClassFile(val missingClasses: List<String>) : CompiledParseResult()

        data class UserCancelled(val className: String) : CompiledParseResult()

        data class Timeout(val timeoutMs: Long) : CompiledParseResult()

        data object Failed : CompiledParseResult()
    }

    /** Immutable preview-specific inputs used while collecting sources for a compilation. */
    data class PreviewCompilationContext(
        val processingId: Long,
        val inputFile: VirtualFile,
        val grammarCharset: java.nio.charset.Charset,
        val lexerGrammarFile: File?,
        val generateListener: Boolean,
        val generateVisitor: Boolean,
        val boundGrammarPath: String,
    )

    /** Compiles or reuses generated classes, then invokes the selected start rule. */
    suspend fun parseWithCompiledClasses(
        g: Grammar,
        lg: LexerGrammar,
        startRuleName: String?,
        grammarFile: VirtualFile,
        inputText: String,
        generatedCodeDir: File,
        project: Project,
        indicator: ProgressIndicator? = null,
        caseChangingStrategy: CaseChangingStrategy? = null,
        previewRuntime: String? = null,
        previewContext: PreviewCompilationContext? = null,
    ): CompiledParseResult {
        val grammarPath = grammarFile.path
        val compilationCacheKey = compilationCacheKey(grammarPath, generatedCodeDir)
        val grammarDir = File(grammarPath).parentFile
        val projectBasePath = project.basePath?.let { File(it) }

        val previewService = project.service<PreviewService>()
        val processingId = previewContext?.processingId ?: previewService.statusOwnerProcessingId()
        val requestedPreviewRuntime = previewRuntime ?: previewService.state.liveSettings?.previewRuntime ?: "Java"
        val requestedCaseStrategy =
            caseChangingStrategy
                ?: previewService.state.liveSettings?.caseChangingStrategy
                ?: CaseChangingStrategy.LEAVE_AS_IS
        val effectiveCaseStrategy = CaseChangingStrategy.getEffectiveStrategy(lg, requestedCaseStrategy)
        val trustManager = TrustedFilesManager.getInstance(project)

        val grammarMutexLease = acquireGrammarMutex(grammarPath)

        return try {
            grammarMutexLease.mutex.withLock {
                try {
                    currentCoroutineContext().ensureActive()
                    // Invalidation bypasses the grammar mutex; capture its epoch to reject late
                    // publication.
                    val compilationCacheEpoch = cacheEpoch(grammarPath)

                    if (!EcjCompiler.isAvailable()) {
                        LOG.warn("Bundled Java compiler unavailable")
                        previewService.appendAntlrLine(
                            "The bundled Java compiler is unavailable. Reinstall ANTLR4+ to restore it.",
                        )
                        return@withLock CompiledParseResult.Failed
                    }

                    LOG.info(
                        "parseWithCompiledClasses() called for grammar: ${g.name}, lexer: ${lg.name}",
                    )
                    LOG.info(
                        "  generatedCodeDir: ${generatedCodeDir.absolutePath}, exists: ${generatedCodeDir.exists()}",
                    )
                    LOG.info(
                        "  grammarDir: ${grammarDir.absolutePath}, exists: ${grammarDir.exists()}",
                    )
                    LOG.info("  projectBasePath: ${projectBasePath?.absolutePath ?: "null"}")

                    // Require project trust before reusing or loading compiled classes.
                    @Suppress("UnstableApiUsage")
                    if (!project.compatIsTrusted()) {
                        LOG.info(
                            "Project is NOT trusted; blocking ALL code loading (cache, disk, compilation)",
                        )
                        previewService.appendAntlrLine("Preview blocked: project is not trusted.")
                        return@withLock CompiledParseResult.GrammarCodeUntrusted
                    }

                    val lexerGrammarPath = lg.fileName
                    val lexerGrammarFile =
                        if (lexerGrammarPath != null && lexerGrammarPath != grammarPath) {
                            val lexerFile = File(lexerGrammarPath)
                            if (lexerFile.exists()) lexerFile else null
                        } else null

                    val lexerClassName = lg.name
                    // Combined grammars append Parser even when the grammar name already ends with
                    // Parser.
                    val isCombinedGrammar = lexerGrammarFile == null
                    val parserClassName =
                        if (isCombinedGrammar) {
                            "${g.name}Parser"
                        } else {
                            g.name
                        }

                    val cached = classLoaderCache[compilationCacheKey]

                    val isCurrentlyKotlin = requestedPreviewRuntime == "Kotlin"

                    val compileOutputDir = File(generatedCodeDir, "compiled")

                    // Disk reuse requires current timestamps and matching generated-file records.
                    if (compileOutputDir.exists()) {
                        val resolvedLexerClass = resolveCompiledClass(compileOutputDir, lexerClassName, log = LOG)
                        val resolvedParserClass = resolveCompiledClass(compileOutputDir, parserClassName, log = LOG)
                        val lexerClassFile = resolvedLexerClass?.file
                        val parserClassFile = resolvedParserClass?.file

                        if (
                            resolvedLexerClass != null &&
                                resolvedParserClass != null &&
                                lexerClassFile != null &&
                                parserClassFile != null
                        ) {
                            val classLastModified = minOf(lexerClassFile.lastModified(), parserClassFile.lastModified())
                            val grammarLastModified = File(grammarPath).lastModified()
                            val generatedSourceLastModified =
                                newestGeneratedSourceTimestamp(generatedCodeDir, log = LOG)
                            val requiredClassTimestamp = maxOf(grammarLastModified, generatedSourceLastModified)

                            // Runtime or package settings can change generated sources without
                            // changing the grammar.
                            if (classLastModified >= requiredClassTimestamp) {
                                // Revalidate project-owned records even when the singleton cache
                                // still has an entry.
                                if (
                                    cached != null &&
                                        cached.lastModified >= requiredClassTimestamp &&
                                        cached.isKotlin == isCurrentlyKotlin
                                ) {
                                    val cacheeTrustCheck = trustManager.checkClassFiles(compileOutputDir)
                                    if (cacheeTrustCheck is TrustedFilesManager.ClassFileCheckResult.Valid) {
                                        LOG.info(
                                            "Using in-memory cached classes for $grammarPath (isKotlin=${cached.isKotlin})",
                                        )
                                        val result =
                                            parserExecution.parse(
                                                cached,
                                                startRuleName,
                                                inputText,
                                                grammarFile,
                                                previewService,
                                                effectiveCaseStrategy,
                                            )
                                        return if (result != null) CompiledParseResult.Success(result)
                                        else CompiledParseResult.Failed
                                    } else {
                                        LOG.info(
                                            "Cache exists but its compiled classes are no longer trusted for $grammarPath",
                                        )
                                    }
                                } else if (cached != null) {
                                    LOG.info(
                                        "Cached entry is stale for $grammarPath: " +
                                            "sourceSnapshot=${cached.lastModified}, required=$requiredClassTimestamp, " +
                                            "cachedKotlin=${cached.isKotlin}, currentKotlin=$isCurrentlyKotlin",
                                    )
                                }

                                // A stale cached snapshot must not fall back to timestamp-only disk
                                // reuse.
                                if (cached == null)
                                    when (val checkResult = trustManager.checkClassFiles(compileOutputDir)) {
                                        is TrustedFilesManager.ClassFileCheckResult.Valid -> {
                                            LOG.info(
                                                "Reusing marked .class files from ${compileOutputDir.absolutePath} (isKotlin=$isCurrentlyKotlin)",
                                            )
                                            val entry =
                                                loadClassesFromDir(
                                                    compileOutputDir,
                                                    resolvedLexerClass.binaryName,
                                                    resolvedParserClass.binaryName,
                                                    classLastModified,
                                                    isCurrentlyKotlin,
                                                )
                                            if (entry != null) {
                                                if (
                                                    !replaceCachedEntry(
                                                        compilationCacheKey,
                                                        grammarPath,
                                                        entry,
                                                        compilationCacheEpoch,
                                                    )
                                                ) {
                                                    return@withLock CompiledParseResult.Failed
                                                }
                                                val result =
                                                    parserExecution.parse(
                                                        entry,
                                                        startRuleName,
                                                        inputText,
                                                        grammarFile,
                                                        previewService,
                                                        effectiveCaseStrategy,
                                                    )
                                                return if (result != null) CompiledParseResult.Success(result)
                                                else CompiledParseResult.Failed
                                            }
                                        }
                                        is TrustedFilesManager.ClassFileCheckResult.TamperedFiles -> {
                                            LOG.warn(
                                                "SECURITY: Compiled class files were modified! Files: ${checkResult.files}",
                                            )
                                            ApplicationManager.getApplication().invokeLater {
                                                Notifications.Bus.notify(
                                                    Notification(
                                                        "ANTLR4Plus",
                                                        "Security Warning: Compiled Classes Modified",
                                                        "Compiled class files were modified after compilation: ${checkResult.files.joinToString(", ")}. " +
                                                            "This could indicate tampering. Regenerating from source.",
                                                        NotificationType.WARNING,
                                                    ),
                                                    project,
                                                )
                                            }
                                            GeneratedWorkspaceFileOps.deleteTree(
                                                compileOutputDir.toPath(),
                                            )
                                        }
                                        is TrustedFilesManager.ClassFileCheckResult.UnmarkedFiles -> {
                                            LOG.info(
                                                "Found unmarked .class files - deleting for regeneration",
                                            )
                                            GeneratedWorkspaceFileOps.deleteTree(
                                                compileOutputDir.toPath(),
                                            )
                                        }
                                        is TrustedFilesManager.ClassFileCheckResult.NoFiles -> {}
                                    }
                            }
                        }
                    }

                    var sourceFiles =
                        sourceCollector.collectSourceFiles(
                            generatedCodeDir,
                            grammarDir,
                            projectBasePath,
                            g,
                            lg,
                            indicator,
                            previewService,
                            previewContext,
                        )

                    if (sourceFiles.wasUserCancelled) {
                        val cancelledClass = sourceFiles.userCancelledClassName
                        LOG.info(
                            "Preview cancelled: User skipped class file selection for '$cancelledClass'",
                        )
                        previewService.appendAntlrLine(
                            "Preview cancelled: Multiple files found for class '$cancelledClass'. " +
                                "Please resolve the conflict or remove duplicate class files.",
                        )
                        return CompiledParseResult.UserCancelled(cancelledClass ?: "unknown")
                    }

                    val supportFilesResult =
                        sourceCollector.findSupportFiles(
                            grammarDir,
                            projectBasePath,
                            indicator,
                            g.name,
                            previewService,
                            previewContext,
                        )

                    if (supportFilesResult.wasUserCancelled) {
                        val cancelledClass = supportFilesResult.userCancelledClassName
                        LOG.info(
                            "Preview cancelled: User skipped class file selection for '$cancelledClass'",
                        )
                        previewService.appendAntlrLine(
                            "Preview cancelled: Multiple files found for class '$cancelledClass'. " +
                                "Please resolve the conflict or remove duplicate class files.",
                        )
                        return CompiledParseResult.UserCancelled(cancelledClass ?: "unknown")
                    }

                    if (supportFilesResult.foundFiles.isNotEmpty()) {
                        LOG.info(
                            "Found ${supportFilesResult.foundFiles.size} support files: ${supportFilesResult.foundFiles.map { it.name }}",
                        )
                        // Preserve the file chosen during base-class conflict resolution.
                        sourceFiles =
                            TrustedFilesManager.SourceFileCollection(
                                generatedFiles = sourceFiles.generatedFiles,
                                baseClassFiles =
                                    (sourceFiles.baseClassFiles + supportFilesResult.foundFiles).distinctBy {
                                        it.nameWithoutExtension
                                    },
                                missingSuperClasses = sourceFiles.missingSuperClasses,
                            )
                    }

                    val allSourceFiles = sourceFiles.allFiles
                    if (allSourceFiles.isEmpty()) {
                        LOG.info(
                            "No source files found for compilation in ${generatedCodeDir.absolutePath}",
                        )
                        return CompiledParseResult.Failed
                    }
                    LOG.info(
                        "Found ${allSourceFiles.size} source files to compile: ${allSourceFiles.map { it.name }}",
                    )
                    if (sourceFiles.baseClassFiles.isNotEmpty()) {
                        LOG.info("Base class files: ${sourceFiles.baseClassFiles.map { it.name }}")
                    }

                    if (sourceFiles.hasMissingSuperClasses) {
                        val missing = sourceFiles.missingSuperClasses
                        LOG.warn(
                            "Cannot generate preview: missing superclass source files: ${missing.joinToString(", ") { "$it.java/.kt" }}",
                        )
                        return CompiledParseResult.MissingSuperClassFile(missing)
                    }

                    indicator?.text = "Checking project trust..."
                    @Suppress("UnstableApiUsage")
                    if (!project.compatIsTrusted()) {
                        LOG.info("Project is NOT trusted; blocking code compilation")
                        previewService.appendAntlrLine("Preview blocked: project is not trusted.")
                        return@withLock CompiledParseResult.GrammarCodeUntrusted
                    }
                    LOG.info("Project is trusted; proceeding with preview")
                    indicator?.text = "Generating preview..."

                    val lastModified = allSourceFiles.maxOfOrNull { it.lastModified() } ?: 0L

                    val entry =
                        if (
                            cached != null &&
                                cached.lastModified >= lastModified &&
                                cached.isKotlin == isCurrentlyKotlin
                        ) {
                            val cacheTrustCheck = trustManager.checkClassFiles(compileOutputDir)
                            if (cacheTrustCheck is TrustedFilesManager.ClassFileCheckResult.Valid) {
                                LOG.info(
                                    "Using cached compiled classes for $grammarPath (isKotlin=${cached.isKotlin})",
                                )
                                cached
                            } else {
                                LOG.info(
                                    "Cache exists but trust store cleared at recompile check - recompiling for $grammarPath",
                                )
                                null
                            }
                        } else {
                            if (cached != null && cached.isKotlin != isCurrentlyKotlin) {
                                LOG.info(
                                    "Cache runtime mismatch at recompile check: cached isKotlin=${cached.isKotlin}, current isKotlin=$isCurrentlyKotlin - recompiling",
                                )
                            }
                            null
                        }

                    if (entry == null && cached != null && removeCachedEntry(compilationCacheKey, cached)) {
                        cached.retire()
                    }

                    val finalEntry =
                        if (entry != null) {
                            entry
                        } else {
                            val filesToCompileGenerated = sourceFiles.generatedFiles
                            val filesToCompileBase = sourceFiles.baseClassFiles

                            if (
                                GeneratedWorkspaceFileOps.exists(compileOutputDir.toPath()) &&
                                    !GeneratedWorkspaceFileOps.deleteTree(compileOutputDir.toPath())
                            ) {
                                LOG.warn(
                                    "Could not replace stale compiler output: ${compileOutputDir.absolutePath}",
                                )
                                return@withLock CompiledParseResult.Failed
                            }

                            val isKotlinPreview = filesToCompileGenerated.any {
                                it.extension == "kt"
                            }
                            LOG.info(
                                "Preview language detected: ${if (isKotlinPreview) "Kotlin" else "Java"}",
                            )

                            if (isKotlinPreview) {
                                if (!KotlinRuntimeCompiler.isAvailable()) {
                                    LOG.warn("Kotlin compiler not available for Kotlin preview")
                                    previewService.appendCompilerLine(
                                        "Bundled Kotlin compiler not available. Reinstall the plugin.",
                                    )
                                    return@withLock CompiledParseResult.Failed
                                }

                                val classpath = KotlinRuntimeCompiler.getAntlrKotlinRuntimeClasspath()
                                LOG.info(
                                    "Kotlin classpath for compilation: ${classpath.map { it.name }}",
                                )

                                val kotlinGeneratedFiles = filesToCompileGenerated.filter {
                                    it.extension == "kt"
                                }
                                val kotlinBaseFiles = filesToCompileBase.filter {
                                    it.extension == "kt"
                                }
                                val allKotlinFiles = kotlinGeneratedFiles + kotlinBaseFiles

                                // The Kotlin compiler can resolve Java sources without emitting
                                // their classes.
                                val droppedJavaBases = filesToCompileBase.filter {
                                    it.extension == "java"
                                }
                                if (droppedJavaBases.isNotEmpty()) {
                                    previewService.reportBaseLanguageMismatch(
                                        droppedJavaBases,
                                        sourceLanguage = "Java",
                                        previewLanguage = "Kotlin",
                                        log = LOG,
                                    )
                                    return@withLock CompiledParseResult.Failed
                                }

                                // Java and Kotlin ANTLR runtime types are incompatible despite
                                // sharing simple names.
                                val wrongRuntimeBases = kotlinBaseFiles.mapNotNull { file ->
                                    val text =
                                        try {
                                            file.readText()
                                        } catch (_: Exception) {
                                            return@mapNotNull null
                                        }
                                    if (containsJavaAntlrRuntimeImport(text)) file else null
                                }
                                if (wrongRuntimeBases.isNotEmpty()) {
                                    previewService.reportKotlinRuntimeMismatch(
                                        wrongRuntimeBases,
                                        log = LOG,
                                    )
                                    return@withLock CompiledParseResult.Failed
                                }

                                LOG.info(
                                    "Compiling ${allKotlinFiles.size} Kotlin files for $grammarPath to ${compileOutputDir.absolutePath}",
                                )

                                val compileStartTime = System.nanoTime()
                                when (
                                    val result =
                                        KotlinRuntimeCompiler.compile(
                                            allKotlinFiles,
                                            classpath,
                                            compileOutputDir,
                                            progressCallback = { message ->
                                                previewService.publishStatus(
                                                    message,
                                                    processingId = processingId,
                                                )
                                            },
                                        )
                                ) {
                                    is KotlinRuntimeCompiler.CompilationResult.Success -> {
                                        val compileTimeMs = (System.nanoTime() - compileStartTime) / 1_000_000.0
                                        val compiledFiles = trustManager.markClassFilesAsGenerated(compileOutputDir)
                                        LOG.info(
                                            "Marked ${compiledFiles.size} .class files as generated (Kotlin)",
                                        )

                                        previewService.appendCompilerLine(
                                            "Kotlin compilation successful: ${allKotlinFiles.size} source files → ${compiledFiles.size} classes (${String.format("%.0f", compileTimeMs)} ms)",
                                        )

                                        LOG.info(
                                            "Looking for lexer class: $lexerClassName, parser class: $parserClassName",
                                        )

                                        val resolvedLexerClass =
                                            resolveCompiledClass(
                                                compileOutputDir,
                                                lexerClassName,
                                                log = LOG,
                                            ) ?: return@withLock CompiledParseResult.Failed
                                        val resolvedParserClass =
                                            resolveCompiledClass(
                                                compileOutputDir,
                                                parserClassName,
                                                log = LOG,
                                            ) ?: return@withLock CompiledParseResult.Failed

                                        val lexerClass =
                                            result.classLoader.loadClassOrNull(
                                                resolvedLexerClass.binaryName,
                                                "Kotlin lexer",
                                            )

                                        val parserClass =
                                            result.classLoader.loadClassOrNull(
                                                resolvedParserClass.binaryName,
                                                "Kotlin parser",
                                            )

                                        val newEntry =
                                            CompiledClassLoaderEntry(
                                                result.classLoader,
                                                lexerClass,
                                                parserClass,
                                                lastModified,
                                                isKotlin = true,
                                                log = LOG,
                                            )
                                        if (
                                            replaceCachedEntry(
                                                compilationCacheKey,
                                                grammarPath,
                                                newEntry,
                                                compilationCacheEpoch,
                                            )
                                        ) {
                                            newEntry
                                        } else {
                                            return@withLock CompiledParseResult.Failed
                                        }
                                    }
                                    is KotlinRuntimeCompiler.CompilationResult.Failure -> {
                                        val errorSummary = result.errors.joinToString("; ")
                                        LOG.warn("Kotlin compilation failed: $errorSummary")

                                        previewService.appendCompilerLine(
                                            "Kotlin compilation failed:",
                                        )
                                        result.errors.forEach { error ->
                                            previewService.appendCompilerLine("  $error")
                                        }
                                        return@withLock CompiledParseResult.Failed
                                    }
                                }
                            } else {
                                val classpath = RuntimeCompiler.getAntlrRuntimeClasspath()
                                LOG.info("Classpath for compilation: ${classpath.map { it.name }}")

                                val javaGeneratedFiles = filesToCompileGenerated.filter {
                                    it.extension == "java"
                                }
                                val javaBaseFiles = filesToCompileBase.filter {
                                    it.extension == "java"
                                }
                                val allJavaFiles = javaGeneratedFiles + javaBaseFiles

                                // ECJ cannot compile Kotlin base classes.
                                val droppedKotlinBases = filesToCompileBase.filter {
                                    it.extension == "kt"
                                }
                                if (droppedKotlinBases.isNotEmpty()) {
                                    previewService.reportBaseLanguageMismatch(
                                        droppedKotlinBases,
                                        sourceLanguage = "Kotlin",
                                        previewLanguage = "Java",
                                        log = LOG,
                                    )
                                    return@withLock CompiledParseResult.Failed
                                }

                                LOG.info(
                                    "Compiling ${allJavaFiles.size} Java files for $grammarPath to ${compileOutputDir.absolutePath}",
                                )
                                LOG.info(
                                    "  Generated files: ${javaGeneratedFiles.size}, Base class files: ${javaBaseFiles.size}",
                                )
                                val javaCompileStartTime = System.nanoTime()
                                when (
                                    val result =
                                        RuntimeCompiler.compileTwoPasses(
                                            javaGeneratedFiles,
                                            javaBaseFiles,
                                            classpath,
                                            compileOutputDir,
                                        )
                                ) {
                                    is RuntimeCompiler.CompilationResult.Success -> {
                                        val javaCompileTimeMs = (System.nanoTime() - javaCompileStartTime) / 1_000_000.0
                                        val compiledFiles = trustManager.markClassFilesAsGenerated(compileOutputDir)
                                        LOG.info(
                                            "Marked ${compiledFiles.size} .class files as generated",
                                        )

                                        previewService.appendCompilerLine(
                                            "Java compilation successful: ${allJavaFiles.size} source files → ${compiledFiles.size} classes (${String.format("%.0f", javaCompileTimeMs)} ms)",
                                        )

                                        LOG.info(
                                            "Looking for lexer class: $lexerClassName, parser class: $parserClassName",
                                        )

                                        val resolvedLexerClass =
                                            resolveCompiledClass(
                                                compileOutputDir,
                                                lexerClassName,
                                                log = LOG,
                                            ) ?: return@withLock CompiledParseResult.Failed
                                        val resolvedParserClass =
                                            resolveCompiledClass(
                                                compileOutputDir,
                                                parserClassName,
                                                log = LOG,
                                            ) ?: return@withLock CompiledParseResult.Failed

                                        val lexerClass =
                                            result.classLoader.loadClassOrNull(
                                                resolvedLexerClass.binaryName,
                                                "lexer",
                                            )

                                        val parserClass =
                                            result.classLoader.loadClassOrNull(
                                                resolvedParserClass.binaryName,
                                                "parser",
                                            )

                                        val newEntry =
                                            CompiledClassLoaderEntry(
                                                result.classLoader,
                                                lexerClass,
                                                parserClass,
                                                lastModified,
                                                log = LOG,
                                            )
                                        if (
                                            replaceCachedEntry(
                                                compilationCacheKey,
                                                grammarPath,
                                                newEntry,
                                                compilationCacheEpoch,
                                            )
                                        ) {
                                            newEntry
                                        } else {
                                            return@withLock CompiledParseResult.Failed
                                        }
                                    }
                                    is RuntimeCompiler.CompilationResult.Failure -> {
                                        val errorSummary = result.errors.joinToString("; ")
                                        LOG.warn("Compilation failed: $errorSummary")

                                        previewService.appendCompilerLine("Compilation failed:")
                                        result.errors.forEach { error ->
                                            previewService.appendCompilerLine("  $error")
                                        }
                                        return@withLock CompiledParseResult.Failed
                                    }
                                }
                            }
                        }

                    val parseResult =
                        parserExecution.parse(
                            finalEntry,
                            startRuleName,
                            inputText,
                            grammarFile,
                            previewService,
                            effectiveCaseStrategy,
                        )
                    if (parseResult != null) CompiledParseResult.Success(parseResult) else CompiledParseResult.Failed
                } catch (failure: Throwable) {
                    rethrowFatalOrCancellation(failure)
                    LOG.warn("Error parsing with compiled classes: ${failure.message}", failure)
                    previewService.appendAntlrLine(
                        "Error during parsing: ${failure.message ?: failure::class.java.simpleName}",
                    )
                    CompiledParseResult.Failed
                }
            }
        } finally {
            releaseGrammarMutex(grammarPath, grammarMutexLease)
        }
    }

    /** Loads compiled classes with the matching runtime on the classpath. */
    private fun loadClassesFromDir(
        compileOutputDir: File,
        lexerClassName: String,
        parserClassName: String,
        lastModified: Long,
        isKotlin: Boolean = false,
    ): CompiledClassLoaderEntry? {
        val pluginClassLoader = RuntimeCompiler::class.java.classLoader
        val urls =
            if (isKotlin) {
                arrayOf(compileOutputDir.toURI().toURL()) +
                    KotlinRuntimeCompiler.getAntlrKotlinRuntimeClasspath().map { it.toURI().toURL() }.toTypedArray()
            } else {
                arrayOf(compileOutputDir.toURI().toURL())
            }
        val classLoader = URLClassLoader(urls, pluginClassLoader)

        val lexerClass = classLoader.loadClassOrNull(lexerClassName, "existing lexer")

        val parserClass = classLoader.loadClassOrNull(parserClassName, "existing parser")

        return if (lexerClass != null && parserClass != null) {
            CompiledClassLoaderEntry(
                classLoader,
                lexerClass,
                parserClass,
                lastModified,
                isKotlin,
                log = LOG,
            )
        } else {
            try {
                classLoader.close()
            } catch (e: Exception) {}
            null
        }
    }

    private fun ClassLoader.loadClassOrNull(className: String, description: String): Class<*>? =
        try {
            loadClass(className)
        } catch (e: ClassNotFoundException) {
            LOG.warn("Could not load $description class: $className")
            null
        }

    /**
     * Publishes only if no external invalidation occurred since compilation started. The epoch check and map write
     * share one lock with invalidation, closing the check/put race.
     */
    private fun replaceCachedEntry(
        cacheKey: String,
        grammarPath: String,
        entry: CompiledClassLoaderEntry,
        expectedEpoch: Long,
    ): Boolean {
        val (published, displaced) =
            synchronized(cacheLifecycleLock) {
                if ((cacheEpochs[grammarPath] ?: 0L) != expectedEpoch) {
                    false to null
                } else {
                    true to classLoaderCache.put(cacheKey, entry)
                }
            }
        if (!published) {
            entry.retire()
            return false
        }
        if (displaced != null && displaced !== entry) {
            displaced.retire()
        }
        return true
    }

    private fun removeCachedEntry(cacheKey: String, expected: CompiledClassLoaderEntry): Boolean =
        synchronized(cacheLifecycleLock) { classLoaderCache.remove(cacheKey, expected) }

    data class SupportFilesResult(
        val foundFiles: List<File>,
        val referencedClasses: Set<String>,
        val missingClasses: Set<String>,
        val sourceGrammarFiles: Set<String>,
        val userCancelledClassName: String? = null,
    ) {
        val wasUserCancelled: Boolean
            get() = userCancelledClassName != null
    }

    /** Clears dependency choices and their project-tree decorations. */
    fun clearAllSessionState() = dependencyResolver.clearAllSessionState()

    fun hasConfirmedNoConflictsForFolder(folderPath: String): Boolean =
        dependencyResolver.hasConfirmedNoConflictsForFolder(folderPath)

    /** Uses an absolute input-folder path and a project-relative bound grammar path. */
    fun hasResolvedConflictsForBinding(g4pFolderPath: String, boundGrammarPath: String?): Boolean =
        dependencyResolver.hasResolvedConflictsForBinding(g4pFolderPath, boundGrammarPath)

    /** Resets choices for every input in this folder bound to the given grammar. */
    fun clearConflictResolutionForBinding(g4pFolderPath: String, boundGrammarPath: String) =
        dependencyResolver.clearConflictResolutionForBinding(g4pFolderPath, boundGrammarPath)

    /** Resolves Java/Kotlin source conflicts before compilation; null means cancellation. */
    suspend fun preResolveExternalJavaFiles(
        grammarFile: VirtualFile,
        lexerGrammar: LexerGrammar?,
        parserGrammar: Grammar?,
        project: Project,
        previewService: PreviewService,
        g4pFolder: File? = null,
    ): PreResolveResult? =
        sourceCollector.preResolveExternalJavaFiles(
            grammarFile,
            lexerGrammar,
            parserGrammar,
            project,
            previewService,
            g4pFolder,
        )

    /** Retires cached loaders and schedules output cleanup after their active parses finish. */
    fun clearCache() {
        val entries =
            synchronized(cacheLifecycleLock) {
                // Invalidate unpublished compiles too, so they cannot repopulate the cleared cache.
                (classLoaderCache.keys.map(::grammarPathFromCacheKey) + grammarMutexes.keys).forEach { grammarPath ->
                    cacheEpochs[grammarPath] = (cacheEpochs[grammarPath] ?: 0L) + 1L
                }
                classLoaderCache.entries
                    .map { it.key to it.value }
                    .also {
                        classLoaderCache.clear()
                    }
            }

        entries.forEach { (cacheKey, entry) ->
            val grammarPath = grammarPathFromCacheKey(cacheKey)
            val directoriesToDelete =
                entry.classLoader.urLs.mapNotNull { url ->
                    try {
                        File(url.toURI()).takeIf { it.isDirectory && it.name == "compiled" }
                    } catch (e: Exception) {
                        LOG.debug("Could not parse URL as directory: $url")
                        null
                    }
                }
            entry.retire {
                synchronized(cacheLifecycleLock) {
                    // Delayed retirement must not delete output belonging to a replacement parse.
                    if (classLoaderCache[cacheKey] == null && !grammarMutexes.containsKey(grammarPath)) {
                        directoriesToDelete.forEach(::deleteCompiledDirectory)
                    }
                }
            }
        }
        dependencyResolver.clearCachedChoices()
    }

    private fun deleteCompiledDirectory(directory: File) {
        try {
            if (GeneratedWorkspaceFileOps.exists(directory.toPath())) {
                GeneratedWorkspaceFileOps.deleteTree(directory.toPath())
                LOG.info("Deleted compiled class directory: ${directory.absolutePath}")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to delete compiled directory ${directory.absolutePath}: ${e.message}")
        }
    }

    fun invalidateCache(grammarPath: String) {
        val entries =
            synchronized(cacheLifecycleLock) {
                cacheEpochs[grammarPath] = (cacheEpochs[grammarPath] ?: 0L) + 1L
                classLoaderCache.entries
                    .filter { grammarPathFromCacheKey(it.key) == grammarPath }
                    .map { it.key to it.value }
                    .also { matches ->
                        matches.forEach { (key, _) -> classLoaderCache.remove(key) }
                    }
            }
        entries.forEach { (_, entry) -> entry.retire() }
    }
}
