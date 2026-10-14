package com.antlr4plus.benchmark

import com.antlr4plus.parsing.CaseChangingStrategy
import com.antlr4plus.parsing.KotlinRuntimeCompiler
import com.antlr4plus.parsing.TrustedFilesManager
import com.antlr4plus.parsing.kotlin.KotlinLexerFactory
import com.antlr4plus.parsing.readInputText
import com.antlr4plus.settings.GrammarProperties
import com.antlr4plus.state.PreviewState
import com.antlr4plus.util.rethrowFatalOrCancellation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.Closeable
import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.LexerGrammar

/**
 * Benchmarks parser construction and execution after loading the input and parser classes. Timed iterations include
 * stream creation, reflection, profiling, and embedded grammar code.
 */
class BenchmarkRunner {

    companion object {
        private val LOG = Logger.getInstance(BenchmarkRunner::class.java)

        /**
         * Captures settings and text before background execution; supplied preview text avoids rereading the editor.
         */
        fun captureInput(previewState: PreviewState, inputText: String? = null): BenchmarkInput? {
            val grammar = previewState.grammar ?: return null
            val lexerGrammar = previewState.lexerGrammar ?: return null
            val grammarFile = previewState.grammarFile ?: return null
            val g4pFile = previewState.g4pInputFile ?: return null
            val startRuleName = previewState.startRuleName ?: return null
            val liveSettings = previewState.liveSettings
            val capturedText =
                inputText
                    ?: try {
                        readInputText(g4pFile)
                    } catch (e: Exception) {
                        rethrowFatalOrCancellation(e)
                        LOG.warn("BENCHMARK ABORT: Failed to read input file: ${e.message}")
                        return null
                    }
            return BenchmarkInput(
                grammar = grammar,
                lexerGrammar = lexerGrammar,
                grammarFile = grammarFile,
                g4pFile = g4pFile,
                inputText = capturedText,
                startRuleName = startRuleName,
                previewRuntime = liveSettings?.previewRuntime ?: "Java",
                configuredOutputDir = liveSettings?.outputDir,
                packageName = liveSettings?.packageName?.takeIf { it.isNotBlank() },
                caseChangingStrategy = liveSettings?.caseChangingStrategy ?: CaseChangingStrategy.LEAVE_AS_IS,
            )
        }
    }

    data class BenchmarkInput(
        val grammar: Grammar,
        val lexerGrammar: LexerGrammar,
        val grammarFile: VirtualFile,
        val g4pFile: VirtualFile,
        val inputText: String,
        val startRuleName: String,
        val previewRuntime: String,
        val configuredOutputDir: String?,
        val packageName: String?,
        val caseChangingStrategy: CaseChangingStrategy,
    )

    data class BenchmarkConfig(
        val warmupDurationMs: Long = 200,
        val measurementDurationMs: Long = 500,
        val singleParseTimeoutMs: Long =
            800, // Stop after a completed parse exceeds this duration; does not interrupt it.
        val parsingTimeoutMs: Long =
            1000, // Budget for warmup and the final profile parse; measurement has its own window.
        val name: String = "Parsing Benchmark",
    )

    data class ProfilerStats(
        val totalPredictionTimeNs: Long,
        val totalSLLLookaheadOps: Long,
        val totalLLLookaheadOps: Long,
        val totalATNLookaheadOps: Long,
        val numTokens: Int,
    ) {
        val totalLookaheadOps: Long
            get() = totalSLLLookaheadOps + totalLLLookaheadOps

        val lookaheadBurden: Double
            get() = if (numTokens > 0) totalLookaheadOps.toDouble() / numTokens else 0.0

        val cacheMissRate: Double
            get() = if (totalLookaheadOps > 0) totalATNLookaheadOps * 100.0 / totalLookaheadOps else 0.0

        val predictionTimeMs: Double
            get() = totalPredictionTimeNs / 1_000_000.0
    }

    /** Caches primary constructors and the start method; owns the benchmark classloader. */
    private class BenchmarkContext(
        val lexerConstructor: java.lang.reflect.Constructor<*>,
        val parserConstructor: java.lang.reflect.Constructor<*>,
        val startMethod: Method,
        val inputText: String,
        val sourceName: String,
        val caseStrategy: CaseChangingStrategy,
        val classLoader: URLClassLoader,
        val isKotlin: Boolean = false,
        val kotlinLexerFactory: KotlinLexerFactory? = null,
        val commonTokenStreamClass: Class<*>? = null,
        val tokenSourceClass: Class<*>? = null,
        val predictionModeClass: Class<*>? = null,
        val llExactAmbig: Any? = null,
    ) : Closeable {
        override fun close() {
            try {
                classLoader.close()
            } catch (failure: Exception) {
                rethrowFatalOrCancellation(failure)
            }
        }
    }

    data class BenchmarkResult(
        val name: String,
        val iterations: Int,
        val meanNs: Double,
        val medianNs: Double,
        val stdDevNs: Double,
        val minNs: Long,
        val maxNs: Long,
        val throughputOpsPerSec: Double,
        val allTimesNs: List<Long>,
        val coefficientOfVariation: Double,
        val inputChars: Int,
        val inputLines: Int,
        val usedCompiledClasses: Boolean = true,
        // Averaged across successful measured iterations.
        val avgPredictionTimeMs: Double = 0.0,
        val avgPredictionPercent: Double = 0.0,
        val avgLookaheadBurden: Double = 0.0,
        val avgCacheMissRate: Double = 0.0,
        val numTokens: Int = 0,
        // Java parser for the decision table; retains its token stream and parse tree.
        val lastParser: Parser? = null,
    ) {
        val meanMs: Double
            get() = meanNs / 1_000_000.0

        val stdDevMs: Double
            get() = stdDevNs / 1_000_000.0
    }

    /** Runs on Dispatchers.Default, checking cancellation and time budgets between parses. */
    suspend fun runParsingBenchmark(
        input: BenchmarkInput,
        project: Project,
        config: BenchmarkConfig = BenchmarkConfig(),
    ): BenchmarkResult? =
        withContext(Dispatchers.Default) {
            LOG.debug("Benchmark started")

            check(!SwingUtilities.isEventDispatchThread()) {
                "BenchmarkRunner.runParsingBenchmark() is running on EDT! " +
                    "Dispatchers.Default should not resolve to EDT. Check coroutine configuration."
            }

            val grammar = input.grammar
            val lexerGrammar = input.lexerGrammar
            val grammarFile = input.grammarFile
            val g4pFile = input.g4pFile
            val startRuleName = input.startRuleName

            LOG.debug(
                "Benchmark params: grammar=${grammar.name}, lexer=${lexerGrammar.name}, startRule=$startRuleName",
            )
            LOG.debug("Grammar file: ${grammarFile.path}")
            LOG.debug("Input file: ${g4pFile.path}")

            val inputText = input.inputText
            LOG.debug("Input text length: ${inputText.length} chars")

            val inputCharsCount = inputText.length
            val inputLines = inputText.count { it == '\n' } + 1

            val g4pParent = g4pFile.parent
            if (g4pParent == null) {
                LOG.warn("BENCHMARK ABORT: g4pFile has no parent directory")
                return@withContext null
            }
            val previewRuntime = input.previewRuntime
            val useKotlinPreview = previewRuntime == "Kotlin"
            val generatedCodeDir =
                File(
                    GrammarProperties.resolvePreviewOutputDir(
                        project = project,
                        contentRoot = g4pParent,
                        configuredOutputDir = input.configuredOutputDir,
                        runtime = previewRuntime,
                    ),
                )
            LOG.debug("Generated code dir: ${generatedCodeDir.absolutePath}")

            val compileOutputDir = File(generatedCodeDir, "compiled")
            val lexerClassName = lexerGrammar.name
            LOG.debug(
                "Compile output dir: ${compileOutputDir.absolutePath}, exists: ${compileOutputDir.exists()}",
            )

            val packageName = input.packageName
            val packageSubDir = packageName?.replace('.', File.separatorChar) ?: ""
            val classFileDir =
                if (packageSubDir.isNotEmpty()) {
                    File(compileOutputDir, packageSubDir)
                } else {
                    compileOutputDir
                }
            LOG.debug(
                "Class file dir: ${classFileDir.absolutePath}, exists: ${classFileDir.exists()}",
            )

            val parserClassName = run {
                val baseName = grammar.name
                val baseClassFile = File(classFileDir, "$baseName.class")
                val parserClassFile = File(classFileDir, "${baseName}Parser.class")
                when {
                    baseClassFile.exists() -> baseName
                    parserClassFile.exists() -> "${baseName}Parser"
                    baseName.endsWith("Parser") -> baseName
                    else -> "${baseName}Parser"
                }
            }
            val lexerClassFile = File(classFileDir, "$lexerClassName.class")
            val parserClassFile = File(classFileDir, "$parserClassName.class")

            if (!lexerClassFile.exists() || !parserClassFile.exists()) {
                LOG.warn(
                    "BENCHMARK ABORT: Class files not found - lexer: ${lexerClassFile.exists()}, parser: ${parserClassFile.exists()}",
                )
                LOG.debug("Files in classFileDir: ${classFileDir.listFiles()?.map { it.name }}")
                return@withContext null
            }

            val trustManager = TrustedFilesManager.getInstance(project)

            // Require class files to match this session's recorded hashes before loading.
            when (val classFileCheckResult = trustManager.checkClassFiles(compileOutputDir)) {
                is TrustedFilesManager.ClassFileCheckResult.Valid -> {}
                is TrustedFilesManager.ClassFileCheckResult.TamperedFiles -> {
                    LOG.warn("BENCHMARK ABORT: Class files tampered: ${classFileCheckResult.files}")
                    return@withContext null
                }
                is TrustedFilesManager.ClassFileCheckResult.UnmarkedFiles -> {
                    LOG.warn("BENCHMARK ABORT: Class files unmarked (not generated by plugin)")
                    return@withContext null
                }
                is TrustedFilesManager.ClassFileCheckResult.NoFiles -> {
                    LOG.warn("BENCHMARK ABORT: No class files found for trust check")
                    return@withContext null
                }
            }

            val lexerFQN = if (packageName != null) "$packageName.$lexerClassName" else lexerClassName
            val parserFQN = if (packageName != null) "$packageName.$parserClassName" else parserClassName

            val context =
                try {
                    prepareBenchmarkContext(
                        compileOutputDir = compileOutputDir,
                        lexerClassName = lexerFQN,
                        parserClassName = parserFQN,
                        grammarName = if (packageName != null) "$packageName.${grammar.name}" else grammar.name,
                        startRuleName = startRuleName,
                        inputText = inputText,
                        sourceName = grammarFile.path,
                        caseStrategy =
                            CaseChangingStrategy.getEffectiveStrategy(
                                lexerGrammar,
                                input.caseChangingStrategy,
                            ),
                        useKotlinRuntime = useKotlinPreview,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    rethrowFatalOrCancellation(e)
                    LOG.warn(
                        "BENCHMARK ABORT: Failed to prepare context: ${e::class.simpleName}: ${e.message}",
                        e,
                    )
                    return@withContext null
                }
            if (context == null) {
                LOG.warn("BENCHMARK ABORT: prepareBenchmarkContext returned null")
                return@withContext null
            }
            try {

                val singleParseTimeoutNs = config.singleParseTimeoutMs * 1_000_000
                val benchmarkStartTime = System.currentTimeMillis()

                fun isTimedOut(): Boolean = System.currentTimeMillis() - benchmarkStartTime > config.parsingTimeoutMs

                // Run at least one warmup iteration, even for slow grammars.
                var warmupDone = false
                var warmupIterations = 0
                var lastSuccessfulParser: Any? = null
                val warmupEndTime = System.currentTimeMillis() + config.warmupDurationMs
                while (
                    (System.currentTimeMillis() < warmupEndTime || !warmupDone) &&
                        isActive &&
                        (!warmupDone || !isTimedOut())
                ) {
                    ensureActive()
                    val start = System.nanoTime()
                    lastSuccessfulParser = runPureParse(context) ?: return@withContext null
                    val elapsed = System.nanoTime() - start
                    warmupDone = true
                    warmupIterations++

                    if (elapsed > singleParseTimeoutNs) {
                        LOG.debug(
                            "Warmup parse exceeded timeout (${elapsed/1_000_000}ms), stopping",
                        )
                        break
                    }
                    yield()
                }
                LOG.debug("Warmup: $warmupIterations iterations")

                if (!isActive) {
                    LOG.info("Benchmark cancelled during warmup (user switched files)")
                    return@withContext null
                }

                val times = mutableListOf<Long>()
                val profilerStats = mutableListOf<ProfilerStats>()
                // Reset warmed DFA state before starting the measurement window.
                if (config.measurementDurationMs > 0 && !isTimedOut() && isActive) {
                    ensureActive()
                    if (!clearDfa(lastSuccessfulParser, context.isKotlin)) return@withContext null
                }
                val measurementEndTime = System.currentTimeMillis() + config.measurementDurationMs

                // Measurement uses its own window, independent of parsingTimeoutMs.
                while (System.currentTimeMillis() < measurementEndTime && isActive) {
                    ensureActive()
                    val start = System.nanoTime()
                    val parser = runPureParse(context) ?: return@withContext null
                    val elapsed = System.nanoTime() - start

                    times.add(elapsed)
                    lastSuccessfulParser = parser
                    val stats = extractProfilerStats(parser, context.isKotlin, LOG)
                    if (stats != null) profilerStats.add(stats)

                    if (elapsed > singleParseTimeoutNs && times.isNotEmpty()) {
                        LOG.debug(
                            "Single parse exceeded timeout, stopping with ${times.size} iterations",
                        )
                        break
                    }

                    yield()
                }
                LOG.debug("Measurement: ${times.size} iterations")

                if (!isActive) {
                    LOG.info("Benchmark cancelled during measurement (user switched files)")
                    return@withContext null
                }

                if (times.isEmpty()) {
                    LOG.info(
                        "BENCHMARK ZERO: Measurement window completed without a sample",
                    )
                    return@withContext BenchmarkResult(
                        name = config.name,
                        iterations = 0,
                        meanNs = 0.0,
                        medianNs = 0.0,
                        stdDevNs = 0.0,
                        minNs = 0,
                        maxNs = 0,
                        throughputOpsPerSec = 0.0,
                        allTimesNs = emptyList(),
                        coefficientOfVariation = 0.0,
                        inputChars = inputCharsCount,
                        inputLines = inputLines,
                        usedCompiledClasses = true,
                    )
                }

                // Take a final profile sample only if the overall budget permits it.
                val profilerParser =
                    if (!isTimedOut() && isActive) {
                        ensureActive()
                        // Clear DFA state to collect fresh ATN statistics.
                        if (!clearDfa(lastSuccessfulParser, context.isKotlin)) return@withContext null
                        ensureActive()
                        runPureParse(context) ?: return@withContext null
                    } else {
                        lastSuccessfulParser
                    }

                LOG.info("Benchmark completed: ${times.size} iterations")
                calculateBenchmarkStatistics(
                    config.name,
                    times,
                    profilerStats,
                    inputCharsCount,
                    inputLines,
                    usedCompiledClasses = true,
                    lastParserAny = profilerParser,
                    isKotlin = context.isKotlin,
                )
            } finally {
                context.close()
            }
        }

    private fun closeAndReturnNull(classLoader: URLClassLoader): BenchmarkContext? {
        try {
            classLoader.close()
        } catch (failure: Exception) {
            rethrowFatalOrCancellation(failure)
        }
        return null
    }

    private fun prepareBenchmarkContext(
        compileOutputDir: File,
        lexerClassName: String,
        parserClassName: String,
        grammarName: String,
        startRuleName: String,
        inputText: String,
        sourceName: String,
        caseStrategy: CaseChangingStrategy,
        useKotlinRuntime: Boolean = false,
    ): BenchmarkContext? {
        LOG.debug(
            "prepareBenchmarkContext: lexer=$lexerClassName, parser=$parserClassName, grammar=$grammarName, startRule=$startRuleName, kotlin=$useKotlinRuntime",
        )
        LOG.debug("prepareBenchmarkContext: compileOutputDir=${compileOutputDir.absolutePath}")

        val pluginClassLoader = this::class.java.classLoader
        val urls =
            if (useKotlinRuntime) {
                val kotlinRuntimeJars = KotlinRuntimeCompiler.getAntlrKotlinRuntimeClasspath()
                LOG.debug("Including ${kotlinRuntimeJars.size} Kotlin runtime JARs in classloader")
                arrayOf(compileOutputDir.toURI().toURL()) + kotlinRuntimeJars.map { it.toURI().toURL() }.toTypedArray()
            } else {
                arrayOf(compileOutputDir.toURI().toURL())
            }
        LOG.debug("Creating URLClassLoader with ${urls.size} URLs")
        val classLoader = URLClassLoader(urls, pluginClassLoader)

        LOG.debug("Loading lexer class: $lexerClassName")
        val lexerClass =
            try {
                classLoader.loadClass(lexerClassName)
            } catch (e: ClassNotFoundException) {
                LOG.warn("Failed to load lexer class '$lexerClassName': ${e.message}")
                return closeAndReturnNull(classLoader)
            }
        LOG.debug("Lexer class loaded: ${lexerClass.name}")

        LOG.debug("Loading parser class: $parserClassName")
        val parserClass =
            try {
                classLoader.loadClass(parserClassName)
            } catch (_: ClassNotFoundException) {
                LOG.debug(
                    "Parser class '$parserClassName' not found, trying grammar name: $grammarName",
                )
                try {
                    classLoader.loadClass(grammarName)
                } catch (e2: ClassNotFoundException) {
                    LOG.warn(
                        "Failed to load parser class '$parserClassName' or '$grammarName': ${e2.message}",
                    )
                    return closeAndReturnNull(classLoader)
                }
            }
        LOG.debug("Parser class loaded: ${parserClass.name}")

        return if (useKotlinRuntime) {
            prepareKotlinContext(
                classLoader,
                lexerClass,
                parserClass,
                startRuleName,
                inputText,
                sourceName,
                caseStrategy,
            )
        } else {
            prepareJavaContext(
                classLoader,
                lexerClass,
                parserClass,
                startRuleName,
                inputText,
                sourceName,
                caseStrategy,
            )
        }
    }

    private fun prepareJavaContext(
        classLoader: URLClassLoader,
        lexerClass: Class<*>,
        parserClass: Class<*>,
        startRuleName: String,
        inputText: String,
        sourceName: String,
        caseStrategy: CaseChangingStrategy,
    ): BenchmarkContext? {
        LOG.debug("Looking up lexer constructor (Java CharStream)")
        val lexerConstructor =
            try {
                lexerClass.getConstructor(CharStream::class.java)
            } catch (e: NoSuchMethodException) {
                LOG.warn("Lexer class missing CharStream constructor: ${e.message}")
                return closeAndReturnNull(classLoader)
            }

        LOG.debug("Looking up parser constructor (Java TokenStream)")
        val parserConstructor =
            try {
                parserClass.getConstructor(TokenStream::class.java)
            } catch (e: NoSuchMethodException) {
                LOG.warn("Parser class missing TokenStream constructor: ${e.message}")
                return closeAndReturnNull(classLoader)
            }

        LOG.debug("Looking up start method: $startRuleName")
        val startMethod =
            try {
                parserClass.getMethod(startRuleName)
            } catch (e: NoSuchMethodException) {
                LOG.warn("Parser class missing start method '$startRuleName': ${e.message}")
                LOG.debug(
                    "Available methods: ${parserClass.methods.map { it.name }.distinct().sorted()}",
                )
                return closeAndReturnNull(classLoader)
            }
        LOG.debug("Start method found: ${startMethod.name}")

        return BenchmarkContext(
            lexerConstructor = lexerConstructor,
            parserConstructor = parserConstructor,
            startMethod = startMethod,
            inputText = inputText,
            sourceName = sourceName,
            caseStrategy = caseStrategy,
            classLoader = classLoader,
            isKotlin = false,
        )
    }

    private fun prepareKotlinContext(
        classLoader: URLClassLoader,
        lexerClass: Class<*>,
        parserClass: Class<*>,
        startRuleName: String,
        inputText: String,
        sourceName: String,
        caseStrategy: CaseChangingStrategy,
    ): BenchmarkContext? {
        LOG.debug("Loading Kotlin runtime classes")
        val charStreamClass =
            try {
                classLoader.loadClass("org.antlr.v4.kotlinruntime.CharStream")
            } catch (e: ClassNotFoundException) {
                LOG.warn("Kotlin CharStream class not found: ${e.message}")
                return closeAndReturnNull(classLoader)
            }

        val commonTokenStreamClass =
            try {
                classLoader.loadClass("org.antlr.v4.kotlinruntime.CommonTokenStream")
            } catch (e: ClassNotFoundException) {
                LOG.warn("Kotlin CommonTokenStream class not found: ${e.message}")
                return closeAndReturnNull(classLoader)
            }

        val tokenStreamClass =
            try {
                classLoader.loadClass("org.antlr.v4.kotlinruntime.TokenStream")
            } catch (e: ClassNotFoundException) {
                LOG.warn("Kotlin TokenStream class not found: ${e.message}")
                return closeAndReturnNull(classLoader)
            }

        val tokenSourceClass =
            try {
                classLoader.loadClass("org.antlr.v4.kotlinruntime.TokenSource")
            } catch (e: ClassNotFoundException) {
                LOG.warn("Kotlin TokenSource class not found: ${e.message}")
                return closeAndReturnNull(classLoader)
            }

        val predictionModeClass =
            try {
                classLoader.loadClass("org.antlr.v4.kotlinruntime.atn.PredictionMode")
            } catch (e: ClassNotFoundException) {
                LOG.warn("Kotlin PredictionMode class not found: ${e.message}")
                null
            }

        LOG.debug("Looking up lexer constructor (Kotlin CharStream)")
        val lexerConstructor =
            try {
                lexerClass.getConstructor(charStreamClass)
            } catch (e: NoSuchMethodException) {
                LOG.warn("Lexer class missing Kotlin CharStream constructor: ${e.message}")
                return closeAndReturnNull(classLoader)
            }

        LOG.debug("Looking up parser constructor (Kotlin TokenStream)")
        val parserConstructor =
            try {
                parserClass.getConstructor(tokenStreamClass)
            } catch (e: NoSuchMethodException) {
                LOG.warn("Parser class missing Kotlin TokenStream constructor: ${e.message}")
                return closeAndReturnNull(classLoader)
            }

        LOG.debug("Looking up start method: $startRuleName")
        val startMethod =
            try {
                parserClass.getMethod(startRuleName)
            } catch (e: NoSuchMethodException) {
                LOG.warn("Parser class missing start method '$startRuleName': ${e.message}")
                LOG.debug(
                    "Available methods: ${parserClass.methods.map { it.name }.distinct().sorted()}",
                )
                return closeAndReturnNull(classLoader)
            }
        LOG.debug("Start method found: ${startMethod.name}")

        val llExactAmbig =
            predictionModeClass?.enumConstants?.find {
                (it as Enum<*>).name == "LL_EXACT_AMBIG_DETECTION"
            }

        return BenchmarkContext(
            lexerConstructor = lexerConstructor,
            parserConstructor = parserConstructor,
            startMethod = startMethod,
            inputText = inputText,
            sourceName = sourceName,
            caseStrategy = caseStrategy,
            classLoader = classLoader,
            isKotlin = true,
            kotlinLexerFactory = KotlinLexerFactory(lexerConstructor),
            commonTokenStreamClass = commonTokenStreamClass,
            tokenSourceClass = tokenSourceClass,
            predictionModeClass = predictionModeClass,
            llExactAmbig = llExactAmbig,
        )
    }

    private fun runPureParse(ctx: BenchmarkContext): Any? =
        if (ctx.isKotlin) {
            runKotlinPureParse(ctx)
        } else {
            runJavaPureParse(ctx)
        }

    private fun runJavaPureParse(ctx: BenchmarkContext): Parser? =
        try {
            val charStream = CharStreams.fromString(ctx.inputText, ctx.sourceName)
            val input = ctx.caseStrategy.applyTo(charStream) ?: charStream

            val lexer = ctx.lexerConstructor.newInstance(input) as Lexer
            lexer.removeErrorListeners()

            val tokens = CommonTokenStream(lexer)

            val parser = ctx.parserConstructor.newInstance(tokens) as Parser
            parser.removeErrorListeners()
            parser.interpreter.predictionMode = PredictionMode.LL_EXACT_AMBIG_DETECTION
            parser.setProfile(true)

            ctx.startMethod.invoke(parser) as? ParseTree
            parser
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            rethrowFatalOrCancellation(e)
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            LOG.warn(
                "runJavaPureParse failed: ${cause::class.simpleName}: ${cause.message}",
                cause,
            )
            null
        }

    private fun runKotlinPureParse(ctx: BenchmarkContext): Any? =
        try {
            val lexer = ctx.kotlinLexerFactory!!.create(ctx.inputText, ctx.sourceName, ctx.caseStrategy)

            try {
                val removeErrorListenersMethod = lexer.javaClass.getMethod("removeErrorListeners")
                removeErrorListenersMethod.invoke(lexer)
            } catch (failure: Exception) {
                rethrowFatalOrCancellation(failure)
            }

            val tokenStreamConstructor = ctx.commonTokenStreamClass!!.getConstructor(ctx.tokenSourceClass)
            val tokens = tokenStreamConstructor.newInstance(lexer)

            val parser = ctx.parserConstructor.newInstance(tokens)

            try {
                val removeErrorListenersMethod = parser.javaClass.getMethod("removeErrorListeners")
                removeErrorListenersMethod.invoke(parser)
            } catch (failure: Exception) {
                rethrowFatalOrCancellation(failure)
            }

            try {
                val setProfileMethod = parser.javaClass.getMethod("setProfile", Boolean::class.javaPrimitiveType)
                setProfileMethod.invoke(parser, true)
            } catch (failure: Exception) {
                rethrowFatalOrCancellation(failure)
            }

            if (ctx.predictionModeClass != null && ctx.llExactAmbig != null) {
                try {
                    val getInterpreterMethod = parser.javaClass.getMethod("getInterpreter")
                    val interpreter = getInterpreterMethod.invoke(parser)
                    if (interpreter != null) {
                        val setPredictionModeMethod =
                            interpreter.javaClass.getMethod(
                                "setPredictionMode",
                                ctx.predictionModeClass,
                            )
                        setPredictionModeMethod.invoke(interpreter, ctx.llExactAmbig)
                    }
                } catch (failure: Exception) {
                    rethrowFatalOrCancellation(failure)
                }
            }

            ctx.startMethod.invoke(parser)
            parser
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            rethrowFatalOrCancellation(e)
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            LOG.warn(
                "runKotlinPureParse failed: ${cause::class.simpleName}: ${cause.message}",
                cause,
            )
            null
        }

    /** Clear shared DFA caches through a completed parse, without invoking extra grammar constructors. */
    private fun clearDfa(parser: Any?, isKotlin: Boolean): Boolean {
        if (parser == null) return false
        return try {
            if (!isKotlin) {
                (parser as Parser).interpreter.clearDFA()
            } else {
                val interpreter = requireNotNull(parser.javaClass.getMethod("getInterpreter").invoke(parser))
                interpreter.javaClass.getMethod("clearDFA").invoke(interpreter)
            }
            true
        } catch (failure: Throwable) {
            rethrowFatalOrCancellation(failure)
            LOG.warn("BENCHMARK ABORT: Could not clear parser DFA", failure)
            false
        }
    }
}
