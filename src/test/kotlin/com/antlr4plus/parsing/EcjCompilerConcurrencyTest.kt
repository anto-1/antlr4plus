package com.antlr4plus.parsing

import com.antlr4plus.util.GeneratedWorkspaceFileOps
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.annotation.processing.Processor
import javax.tools.JavaCompiler
import junit.framework.TestCase

class EcjCompilerConcurrencyTest : TestCase() {

    fun `test shared ECJ compiler executions are serialized`() {
        val root = Files.createTempDirectory("antlr4plus-ecj-concurrency-test")
        val delegate = requireNotNull(EcjCompiler.getCompiler())
        val firstEntered = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val taskSequence = AtomicInteger()
        val compiler = blockingCompiler(delegate, taskSequence, firstEntered, secondEntered, release)
        val previousCompiler = getCachedCompiler()
        val executor = Executors.newFixedThreadPool(2)
        var firstFuture: Future<RuntimeCompiler.CompilationResult>? = null
        var secondFuture: Future<RuntimeCompiler.CompilationResult>? = null

        try {
            setCachedCompiler(compiler)
            val firstSource = writeSource(root.resolve("source/First.java"), "public class First {}")
            val secondSource = writeSource(root.resolve("source/Second.java"), "public class Second {}")

            firstFuture =
                executor.submit<RuntimeCompiler.CompilationResult> {
                    EcjCompiler.compile(
                        listOf(firstSource.toFile()),
                        emptyList(),
                        root.resolve("first-output").toFile(),
                    )
                }
            assertTrue(
                "First compilation never reached the compiler",
                firstEntered.await(10, TimeUnit.SECONDS),
            )

            secondFuture =
                executor.submit<RuntimeCompiler.CompilationResult> {
                    secondStarted.countDown()
                    EcjCompiler.compile(
                        listOf(secondSource.toFile()),
                        emptyList(),
                        root.resolve("second-output").toFile(),
                    )
                }
            assertTrue(
                "Second compilation was never started",
                secondStarted.await(10, TimeUnit.SECONDS),
            )

            val executionsOverlapped = secondEntered.await(1, TimeUnit.SECONDS)
            release.countDown()

            val firstResult = firstFuture.get(10, TimeUnit.SECONDS)
            val secondResult = secondFuture.get(10, TimeUnit.SECONDS)
            closeIfSuccessful(firstResult)
            closeIfSuccessful(secondResult)

            assertFalse("The cached ECJ compiler was entered concurrently", executionsOverlapped)
            assertTrue(
                "First compilation failed: $firstResult",
                firstResult is RuntimeCompiler.CompilationResult.Success,
            )
            assertTrue(
                "Second compilation failed: $secondResult",
                secondResult is RuntimeCompiler.CompilationResult.Success,
            )
        } finally {
            release.countDown()
            waitQuietly(firstFuture)
            waitQuietly(secondFuture)
            executor.shutdown()
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow()
            setCachedCompiler(previousCompiler)
            GeneratedWorkspaceFileOps.deleteTree(root)
        }
    }

    private fun blockingCompiler(
        delegate: JavaCompiler,
        taskSequence: AtomicInteger,
        firstEntered: CountDownLatch,
        secondEntered: CountDownLatch,
        release: CountDownLatch,
    ): JavaCompiler =
        Proxy.newProxyInstance(
            EcjCompilerConcurrencyTest::class.java.classLoader,
            arrayOf(JavaCompiler::class.java),
        ) { _, method, args ->
            try {
                val value = method.invoke(delegate, *(args ?: emptyArray()))
                if (method.name == "getTask") {
                    val sequence = taskSequence.incrementAndGet()
                    BlockingCompilationTask(
                        value as JavaCompiler.CompilationTask,
                        if (sequence == 1) firstEntered else secondEntered,
                        release,
                    )
                } else {
                    value
                }
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as JavaCompiler

    private class BlockingCompilationTask(
        private val delegate: JavaCompiler.CompilationTask,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : JavaCompiler.CompilationTask {
        override fun call(): Boolean {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release compiler" }
            return delegate.call()
        }

        override fun setProcessors(processors: MutableIterable<Processor>) = delegate.setProcessors(processors)

        override fun setLocale(locale: Locale) = delegate.setLocale(locale)

        override fun addModules(moduleNames: MutableIterable<String>) = delegate.addModules(moduleNames)
    }

    private fun getCachedCompiler(): JavaCompiler? =
        EcjCompiler::class.java.getDeclaredField("cachedCompiler").run {
            isAccessible = true
            get(null) as JavaCompiler?
        }

    private fun setCachedCompiler(compiler: JavaCompiler?) {
        EcjCompiler::class.java.getDeclaredField("cachedCompiler").run {
            isAccessible = true
            set(null, compiler)
        }
    }

    private fun writeSource(path: java.nio.file.Path, content: String): java.nio.file.Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    private fun closeIfSuccessful(result: RuntimeCompiler.CompilationResult) {
        if (result is RuntimeCompiler.CompilationResult.Success) result.classLoader.close()
    }

    private fun waitQuietly(future: Future<*>?) {
        runCatching { future?.get(10, TimeUnit.SECONDS) }
    }
}
