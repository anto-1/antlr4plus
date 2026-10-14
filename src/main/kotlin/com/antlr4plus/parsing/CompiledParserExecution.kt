@file:Suppress("MISSING_DEPENDENCY_IN_INFERRED_TYPE_ANNOTATION_WARNING")

package com.antlr4plus.parsing

import com.antlr4plus.parsing.kotlin.KotlinLexerFactory
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.util.rethrowFatalOrCancellation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.antlr.intellij.adaptor.parser.SyntaxErrorListener
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Lexer
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.TokenStream
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.tree.ParseTree

/** Wait limit for start-rule invocation; interruption cannot stop uncooperative code. */
private const val PARSE_TIMEOUT_MS = 30_000L

/** Runs already-loaded parsers while retaining their class-loader leases. */
internal class CompiledParserExecution(
    private val parseExecutor: ExecutorService,
    private val log: Logger,
) {
    suspend fun parse(
        entry: CompiledClassLoaderEntry,
        startRuleName: String?,
        inputText: String,
        grammarFile: VirtualFile,
        previewService: PreviewService,
        caseStrategy: CaseChangingStrategy,
    ): ParsingResult? {
        if (!entry.tryAcquireParseLease()) return null
        return try {
            if (entry.isKotlin) {
                parseWithKotlinEntry(
                    entry,
                    startRuleName,
                    inputText,
                    grammarFile,
                    previewService,
                    caseStrategy,
                )
            } else {
                parseWithJavaEntry(
                    entry,
                    startRuleName,
                    inputText,
                    grammarFile,
                    previewService,
                    caseStrategy,
                )
            }
        } finally {
            entry.releaseParseLease()
        }
    }

    private suspend fun parseWithJavaEntry(
        entry: CompiledClassLoaderEntry,
        startRuleName: String?,
        inputText: String,
        grammarFile: VirtualFile,
        previewService: PreviewService,
        caseStrategy: CaseChangingStrategy,
    ): ParsingResult? {
        val lexerClass = entry.lexerClass ?: return null
        val parserClass = entry.parserClass ?: return null

        val charStream = CharStreams.fromString(inputText, grammarFile.path)
        val input = caseStrategy.applyTo(charStream) ?: charStream
        val lexer = createLexer(lexerClass, input) ?: return null

        val syntaxErrorListener = SyntaxErrorListener()
        lexer.removeErrorListeners()
        lexer.addErrorListener(syntaxErrorListener)

        val tokens = CommonTokenStream(lexer)
        val parser = createParser(parserClass, tokens) ?: return null

        parser.interpreter.predictionMode = PredictionMode.LL_EXACT_AMBIG_DETECTION
        parser.setProfile(true)

        parser.removeErrorListeners()
        parser.addErrorListener(syntaxErrorListener)

        val startMethod =
            try {
                if (startRuleName != null) {
                    parserClass.getMethod(startRuleName)
                } else {
                    return null
                }
            } catch (e: NoSuchMethodException) {
                val availableMethods =
                    parserClass.methods
                        .filter {
                            it.parameterCount == 0 && ParseTree::class.java.isAssignableFrom(it.returnType)
                        }
                        .map { it.name }
                        .sorted()
                log.warn(
                    "Could not find start rule method: $startRuleName. Available: $availableMethods",
                )
                previewService.appendAntlrLine(
                    "Start rule '$startRuleName' not found in generated parser.",
                )
                if (availableMethods.isNotEmpty()) {
                    previewService.appendAntlrLine(
                        "Available rules: ${availableMethods.joinToString(", ")}",
                    )
                }
                return null
            }

        // Capture output inside the executor task so registration belongs to the parsing thread.
        previewService.clearRuntimeConsole()
        val runtimeSink = previewService.runtimeOutputSink()
        val tree =
            try {
                val future =
                    submitStartRule(entry, runtimeSink) {
                        startMethod.invoke(parser) as? ParseTree
                    }
                try {
                    runInterruptible(Dispatchers.IO) {
                        future.get(PARSE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                } catch (e: java.util.concurrent.TimeoutException) {
                    log.warn(
                        "SECURITY: Parse operation timed out after ${PARSE_TIMEOUT_MS}ms - possible infinite loop",
                    )
                    previewService.appendAntlrLine(
                        "SECURITY: Parsing timed out after ${PARSE_TIMEOUT_MS / 1000} seconds.",
                    )
                    previewService.appendAntlrLine(
                        "The grammar may contain infinite loops in embedded code.",
                    )
                    return null
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                } finally {
                    // Request interruption on timeout or cancellation; user code may ignore it.
                    if (!future.isDone) future.cancel(true)
                }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                rethrowFatalOrCancellation(e)
                val cause = e.cause
                log.warn("Error invoking start rule: ${e.message}", e)
                previewService.appendAntlrLine(
                    "Error invoking start rule: ${cause?.message ?: e.message}",
                )
                previewService.appendRuntimeLine(
                    "Exception in start rule: ${cause?.javaClass?.name ?: e.javaClass.name}: ${cause?.message ?: e.message}",
                )
                cause?.stackTrace?.take(10)?.forEach { ste ->
                    previewService.appendRuntimeLine("  at $ste")
                }
                return null
            } catch (failure: Throwable) {
                rethrowFatalOrCancellation(failure)
                log.warn("Error invoking start rule: ${failure.message}", failure)
                previewService.appendAntlrLine(
                    "Error invoking start rule: ${failure.cause?.message ?: failure.message ?: failure::class.java.simpleName}",
                )
                return null
            }

        if (tree == null) {
            log.warn("Start rule '$startRuleName' returned null")
            previewService.appendAntlrLine("Start rule '$startRuleName' returned null parse tree.")
            return null
        }

        return ParsingResult.java(parser, tree, syntaxErrorListener)
    }

    /** Uses reflection to keep the isolated Kotlin runtime types outside the plugin ABI. */
    private suspend fun parseWithKotlinEntry(
        entry: CompiledClassLoaderEntry,
        startRuleName: String?,
        inputText: String,
        grammarFile: VirtualFile,
        previewService: PreviewService,
        caseStrategy: CaseChangingStrategy,
    ): ParsingResult? {
        val lexerClass = entry.lexerClass ?: return null
        val parserClass = entry.parserClass ?: return null
        val classLoader = entry.classLoader

        try {
            val charStreamClass = classLoader.loadClass("org.antlr.v4.kotlinruntime.CharStream")
            val commonTokenStreamClass = classLoader.loadClass("org.antlr.v4.kotlinruntime.CommonTokenStream")
            val tokenStreamClass = classLoader.loadClass("org.antlr.v4.kotlinruntime.TokenStream")
            val tokenSourceClass = classLoader.loadClass("org.antlr.v4.kotlinruntime.TokenSource")

            val lexerConstructor = lexerClass.getConstructor(charStreamClass)
            val lexer = KotlinLexerFactory(lexerConstructor).create(inputText, grammarFile.path, caseStrategy)

            val errors = mutableListOf<String>()
            val syntaxErrorListener = SyntaxErrorListener()
            val errorListenerClass = classLoader.loadClass("org.antlr.v4.kotlinruntime.ANTLRErrorListener")
            val errorListener =
                Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(errorListenerClass),
                ) { proxy, method, args ->
                    when (method.name) {
                        "syntaxError" -> {
                            val line = args?.getOrNull(2) as? Int ?: 0
                            val column = args?.getOrNull(3) as? Int ?: 0
                            val message = args?.getOrNull(4)?.toString() ?: "Unknown syntax error"
                            errors.add("line $line:$column $message")
                            syntaxErrorListener.syntaxError(null, null, line, column, message, null)
                            null
                        }
                        "toString" -> "ANTLR4PlusKotlinErrorListener"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> null
                    }
                }
            lexer.javaClass.getMethod("removeErrorListeners").invoke(lexer)
            lexer.javaClass.getMethod("addErrorListener", errorListenerClass).invoke(lexer, errorListener)

            val tokenStreamConstructor = commonTokenStreamClass.getConstructor(tokenSourceClass)
            val tokens = tokenStreamConstructor.newInstance(lexer)

            val parserConstructor = parserClass.getConstructor(tokenStreamClass)
            val parser = parserConstructor.newInstance(tokens)
            parser.javaClass.getMethod("removeErrorListeners").invoke(parser)
            parser.javaClass.getMethod("addErrorListener", errorListenerClass).invoke(parser, errorListener)

            try {
                val setProfileMethod = parser.javaClass.getMethod("setProfile", Boolean::class.javaPrimitiveType)
                setProfileMethod.invoke(parser, true)

                val getInterpreterMethod = parser.javaClass.getMethod("getInterpreter")
                val interpreter = getInterpreterMethod.invoke(parser)
                if (interpreter != null) {
                    val predictionModeClass = classLoader.loadClass("org.antlr.v4.kotlinruntime.atn.PredictionMode")
                    val llExactAmbig =
                        predictionModeClass.enumConstants?.find {
                            (it as Enum<*>).name == "LL_EXACT_AMBIG_DETECTION"
                        }
                    if (llExactAmbig != null) {
                        val setPredictionModeMethod =
                            interpreter.javaClass.getMethod(
                                "setPredictionMode",
                                predictionModeClass,
                            )
                        setPredictionModeMethod.invoke(interpreter, llExactAmbig)
                    }
                }
                log.info("Kotlin parser profiling enabled")
            } catch (e: Exception) {
                rethrowFatalOrCancellation(e)
                log.warn("Could not enable Kotlin parser profiling: ${e.message}")
            }

            val startMethod =
                try {
                    if (startRuleName != null) {
                        parserClass.getMethod(startRuleName)
                    } else {
                        return null
                    }
                } catch (e: NoSuchMethodException) {
                    val availableMethods =
                        parserClass.methods
                            .filter { it.parameterCount == 0 }
                            .map { it.name }
                            .filter {
                                !it.startsWith("get") &&
                                    !it.startsWith("set") &&
                                    it != "hashCode" &&
                                    it != "toString" &&
                                    it != "equals"
                            }
                            .sorted()
                    log.warn(
                        "Could not find Kotlin start rule method: $startRuleName. Available: $availableMethods",
                    )
                    previewService.appendAntlrLine(
                        "Start rule '$startRuleName' not found in generated Kotlin parser.",
                    )
                    if (availableMethods.isNotEmpty()) {
                        previewService.appendAntlrLine(
                            "Available rules: ${availableMethods.joinToString(", ")}",
                        )
                    }
                    return null
                }

            previewService.clearRuntimeConsole()
            val runtimeSink = previewService.runtimeOutputSink()
            val tree =
                try {
                    val future =
                        submitStartRule(entry, runtimeSink) {
                            startMethod.invoke(parser)
                        }
                    try {
                        runInterruptible(Dispatchers.IO) {
                            future.get(PARSE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                        }
                    } catch (e: java.util.concurrent.TimeoutException) {
                        log.warn(
                            "SECURITY: Kotlin parse operation timed out after ${PARSE_TIMEOUT_MS}ms",
                        )
                        previewService.appendAntlrLine(
                            "SECURITY: Parsing timed out after ${PARSE_TIMEOUT_MS / 1000} seconds.",
                        )
                        return null
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw e.cause ?: e
                    } finally {
                        if (!future.isDone) future.cancel(true)
                    }
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    rethrowFatalOrCancellation(e)
                    log.warn("Error invoking Kotlin start rule: ${e.cause?.message}", e)
                    previewService.appendAntlrLine(
                        "Error invoking start rule: ${e.cause?.message ?: e.message}",
                    )
                    val cause = e.cause
                    previewService.appendRuntimeLine(
                        "Exception in start rule: ${cause?.javaClass?.name ?: e.javaClass.name}: ${cause?.message ?: e.message}",
                    )
                    cause?.stackTrace?.take(10)?.forEach { ste ->
                        previewService.appendRuntimeLine("  at $ste")
                    }
                    return null
                } catch (failure: Throwable) {
                    rethrowFatalOrCancellation(failure)
                    log.warn("Error invoking Kotlin start rule: ${failure.message}", failure)
                    previewService.appendAntlrLine(
                        "Error invoking start rule: ${failure.message ?: failure::class.java.simpleName}",
                    )
                    return null
                }

            if (tree == null) {
                log.warn("Kotlin start rule '$startRuleName' returned null")
                previewService.appendAntlrLine(
                    "Start rule '$startRuleName' returned null parse tree.",
                )
                return null
            }

            log.info("Kotlin parsing successful: ${tree.javaClass.simpleName}")
            return ParsingResult.kotlin(parser, tree, classLoader, errors, syntaxErrorListener)
        } catch (failure: Throwable) {
            rethrowFatalOrCancellation(failure)
            log.warn("Kotlin parsing failed: ${failure.message}", failure)
            previewService.appendAntlrLine(
                "Kotlin parsing error: ${failure.message ?: failure::class.java.simpleName}",
            )
            return null
        }
    }

    private inline fun <T> submitStartRule(
        entry: CompiledClassLoaderEntry,
        noinline runtimeSink: ((String) -> Unit)?,
        crossinline action: () -> T,
    ): Future<T> =
        submitParserTask(parseExecutor, entry) {
            if (runtimeSink != null) {
                RuntimeStdoutCapture.runWithCaptureOnCurrentThread(runtimeSink) {
                    action()
                }
            } else {
                action()
            }
        }

    private fun createLexer(lexerClass: Class<*>, input: CharStream): Lexer? =
        createInstance("lexer") {
            val constructor = lexerClass.getConstructor(CharStream::class.java)
            constructor.newInstance(input) as Lexer
        }

    private fun createParser(parserClass: Class<*>, tokens: TokenStream): Parser? =
        createInstance("parser") {
            val constructor = parserClass.getConstructor(TokenStream::class.java)
            constructor.newInstance(tokens) as Parser
        }

    private inline fun <T> createInstance(kind: String, factory: () -> T): T? =
        try {
            factory()
        } catch (e: java.lang.reflect.InvocationTargetException) {
            rethrowFatalOrCancellation(e)
            log.warn("Could not create $kind instance: ${e.message}")
            null
        } catch (failure: Throwable) {
            rethrowFatalOrCancellation(failure)
            log.warn("Could not create $kind instance: ${failure.message}", failure)
            null
        }
}
