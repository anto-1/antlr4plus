package com.antlr4plus.parsing

import com.intellij.openapi.diagnostic.Logger
import java.net.URLClassLoader
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger

/** Retires a compiled loader after its last parse and worker lease is released. */
internal class CompiledClassLoaderEntry(
    val classLoader: URLClassLoader,
    val lexerClass: Class<*>?,
    val parserClass: Class<*>?,
    val lastModified: Long,
    val isKotlin: Boolean = false,
    private val log: Logger,
) {
    private val lifecycleLock = Any()
    private var activeParses = 0
    private var retired = false
    private var closeStarted = false
    private var closeCompleted = false
    private val afterClose = mutableListOf<() -> Unit>()

    fun tryAcquireParseLease(): Boolean =
        synchronized(lifecycleLock) {
            if (retired) return@synchronized false
            activeParses++
            true
        }

    fun releaseParseLease() {
        val shouldClose =
            synchronized(lifecycleLock) {
                check(activeParses > 0) { "Class-loader parse lease released twice" }
                activeParses--
                prepareCloseIfIdle()
            }
        if (shouldClose) performClose()
    }

    /** Adds a worker lease while an already-leased parse hands work to the executor. */
    fun acquireDependentWorkerLease() {
        synchronized(lifecycleLock) {
            check(activeParses > 0 && !closeStarted) {
                "Parser worker lease requires an active parse lease"
            }
            activeParses++
        }
    }

    /** Stops new parses and closes only after every in-flight parse releases its lease. */
    fun retire(callback: (() -> Unit)? = null) {
        var runCallbackNow = false
        val shouldClose =
            synchronized(lifecycleLock) {
                if (closeCompleted) {
                    runCallbackNow = callback != null
                    false
                } else {
                    if (callback != null) afterClose.add(callback)
                    retired = true
                    prepareCloseIfIdle()
                }
            }
        if (shouldClose) performClose()
        if (runCallbackNow) runCloseCallback(requireNotNull(callback))
    }

    private fun prepareCloseIfIdle(): Boolean {
        if (!retired || activeParses != 0 || closeStarted) return false
        closeStarted = true
        return true
    }

    private fun performClose() {
        try {
            classLoader.close()
        } catch (failure: Exception) {
            log.warn("Failed to close retired compiled-parser class loader", failure)
        } finally {
            val callbacks =
                synchronized(lifecycleLock) {
                    closeCompleted = true
                    afterClose.toList().also { afterClose.clear() }
                }
            callbacks.forEach(::runCloseCallback)
        }
    }

    private fun runCloseCallback(callback: () -> Unit) {
        try {
            callback()
        } catch (failure: Exception) {
            log.warn("Compiled-parser class-loader cleanup failed", failure)
        }
    }
}

/**
 * Releases its class-loader lease only when the executor runnable actually exits. Future cancellation marks a Future
 * done before interrupt-ignoring user code stops, so tying the lease to Future completion would still close the loader
 * underneath a running action.
 */
private class ParserFutureTask<T>(
    private val entry: CompiledClassLoaderEntry,
    callable: Callable<T>,
) : FutureTask<T>(callable) {
    private val lifecycle = AtomicInteger(QUEUED)

    init {
        entry.acquireDependentWorkerLease()
    }

    override fun run() {
        if (!lifecycle.compareAndSet(QUEUED, RUNNING)) return
        try {
            super.run()
        } finally {
            releaseWorkerLease()
        }
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val cancelled = super.cancel(mayInterruptIfRunning)
        if (cancelled && lifecycle.compareAndSet(QUEUED, RELEASED)) {
            entry.releaseParseLease()
        }
        return cancelled
    }

    private fun releaseWorkerLease() {
        if (lifecycle.getAndSet(RELEASED) != RELEASED) {
            entry.releaseParseLease()
        }
    }

    companion object {
        private const val QUEUED = 0
        private const val RUNNING = 1
        private const val RELEASED = 2
    }
}

internal fun <T> submitParserTask(
    parseExecutor: ExecutorService,
    entry: CompiledClassLoaderEntry,
    action: () -> T,
): Future<T> {
    val task = ParserFutureTask(entry) { action() }
    try {
        parseExecutor.execute(task)
    } catch (failure: Throwable) {
        task.cancel(false)
        throw failure
    }
    return task
}
