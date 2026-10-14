package com.antlr4plus.parsing

import com.antlr4plus.util.GeneratedWorkspaceFileOps
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import junit.framework.TestCase

class KotlinRuntimeCompilerConcurrencyTest : TestCase() {

    fun `test concurrent compiler executions are serialized and restore IDEA home`() {
        withFakeCompiler { root ->
            val originalIdeaHome = System.getProperty(IDEA_HOME_PROPERTY)
            val initialIdeaHome = root.resolve("initial-idea-home").toString()
            System.setProperty(IDEA_HOME_PROPERTY, initialIdeaHome)

            val firstSource = writeSource(root.resolve("source/First.kt"), "class First")
            val secondSource = writeSource(root.resolve("source/Second.kt"), "class Second")
            val firstEnteredCompiler = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            val secondEnteredCompiler = CountDownLatch(1)
            val releaseCompiler = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            var firstFuture: Future<KotlinRuntimeCompiler.CompilationResult>? = null
            var secondFuture: Future<KotlinRuntimeCompiler.CompilationResult>? = null

            try {
                firstFuture =
                    executor.submit<KotlinRuntimeCompiler.CompilationResult> {
                        KotlinRuntimeCompiler.compile(
                            listOf(firstSource.toFile()),
                            emptyList(),
                            root.resolve("first-output").toFile(),
                        ) { message ->
                            if (message.startsWith("Compiling")) {
                                firstEnteredCompiler.countDown()
                                assertTrue(
                                    "Timed out releasing first compiler",
                                    releaseCompiler.await(10, TimeUnit.SECONDS),
                                )
                            }
                        }
                    }
                assertTrue(
                    "First compilation never reached the compiler",
                    firstEnteredCompiler.await(10, TimeUnit.SECONDS),
                )

                secondFuture =
                    executor.submit<KotlinRuntimeCompiler.CompilationResult> {
                        secondStarted.countDown()
                        KotlinRuntimeCompiler.compile(
                            listOf(secondSource.toFile()),
                            emptyList(),
                            root.resolve("second-output").toFile(),
                        ) { message ->
                            if (message.startsWith("Compiling")) {
                                secondEnteredCompiler.countDown()
                                assertTrue(
                                    "Timed out releasing second compiler",
                                    releaseCompiler.await(10, TimeUnit.SECONDS),
                                )
                            }
                        }
                    }
                assertTrue(
                    "Second compilation was never started",
                    secondStarted.await(10, TimeUnit.SECONDS),
                )

                val executionsOverlapped = secondEnteredCompiler.await(1, TimeUnit.SECONDS)
                releaseCompiler.countDown()

                val firstResult = firstFuture.get(10, TimeUnit.SECONDS)
                val secondResult = secondFuture.get(10, TimeUnit.SECONDS)
                closeIfSuccessful(firstResult)
                closeIfSuccessful(secondResult)

                assertFalse(
                    "The shared Kotlin compiler was entered concurrently",
                    executionsOverlapped,
                )
                assertTrue(
                    "First compilation failed: $firstResult",
                    firstResult is KotlinRuntimeCompiler.CompilationResult.Success,
                )
                assertTrue(
                    "Second compilation failed: $secondResult",
                    secondResult is KotlinRuntimeCompiler.CompilationResult.Success,
                )
                assertEquals(initialIdeaHome, System.getProperty(IDEA_HOME_PROPERTY))
            } finally {
                releaseCompiler.countDown()
                waitQuietly(firstFuture)
                waitQuietly(secondFuture)
                executor.shutdown()
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow()
                restoreProperty(IDEA_HOME_PROPERTY, originalIdeaHome)
            }
        }
    }

    fun `test compiler does not overwrite an IDEA home change it no longer owns`() {
        withFakeCompiler { root ->
            val originalIdeaHome = System.getProperty(IDEA_HOME_PROPERTY)
            val initialIdeaHome = root.resolve("initial-idea-home").toString()
            val replacementIdeaHome = root.resolve("replacement-idea-home").toString()
            System.setProperty(IDEA_HOME_PROPERTY, initialIdeaHome)

            try {
                val source = writeSource(root.resolve("source/Changed.kt"), "class Changed")
                val result =
                    KotlinRuntimeCompiler.compile(
                        listOf(source.toFile()),
                        emptyList(),
                        root.resolve("output").toFile(),
                    ) { message ->
                        if (message.startsWith("Compiling")) {
                            System.setProperty(IDEA_HOME_PROPERTY, replacementIdeaHome)
                        }
                    }
                closeIfSuccessful(result)

                assertTrue(
                    "Compilation failed: $result",
                    result is KotlinRuntimeCompiler.CompilationResult.Success,
                )
                assertEquals(replacementIdeaHome, System.getProperty(IDEA_HOME_PROPERTY))
            } finally {
                restoreProperty(IDEA_HOME_PROPERTY, originalIdeaHome)
            }
        }
    }

    private fun withFakeCompiler(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("antlr4plus-kotlin-compiler-concurrency-test")
        val fakeIdeaHome = Files.createDirectories(root.resolve("fake-idea-home")).toFile()
        val fakeLoader = URLClassLoader(emptyArray(), null)
        val fakeCompiler = FakeCompiler()
        val execMethod =
            fakeCompiler.javaClass
                .getMethod(
                    "exec",
                    PrintStream::class.java,
                    Array<String>::class.java,
                )
                .apply { isAccessible = true }
        val replacements =
            mapOf<String, Any?>(
                "isolatedClassLoader" to fakeLoader,
                "compilerJars" to emptyList<File>(),
                "cachedCompiler" to fakeCompiler,
                "cachedExecMethod" to execMethod,
                "fakeIdeaHomeInitialized" to true,
                "fakeIdeaHomePath" to fakeIdeaHome,
            )
        val previousValues = replacements.keys.associateWith(::getCompilerField)

        try {
            replacements.forEach(::setCompilerField)
            action(root)
        } finally {
            previousValues.forEach(::setCompilerField)
            fakeLoader.close()
            GeneratedWorkspaceFileOps.deleteTree(root)
        }
    }

    private fun getCompilerField(name: String): Any? =
        KotlinRuntimeCompiler::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(null)
        }

    private fun setCompilerField(name: String, value: Any?) {
        KotlinRuntimeCompiler::class.java.getDeclaredField(name).run {
            isAccessible = true
            set(null, value)
        }
    }

    private fun writeSource(path: Path, content: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    private fun closeIfSuccessful(result: KotlinRuntimeCompiler.CompilationResult) {
        if (result is KotlinRuntimeCompiler.CompilationResult.Success) result.classLoader.close()
    }

    private fun waitQuietly(future: Future<*>?) {
        runCatching { future?.get(10, TimeUnit.SECONDS) }
    }

    private fun restoreProperty(name: String, value: String?) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }

    class FakeCompiler {
        @Suppress("UNUSED_PARAMETER") fun exec(stream: PrintStream, args: Array<String>): FakeExitCode = FakeExitCode(0)
    }

    data class FakeExitCode(val code: Int)

    companion object {
        private const val IDEA_HOME_PROPERTY = "idea.home.path"
    }
}
