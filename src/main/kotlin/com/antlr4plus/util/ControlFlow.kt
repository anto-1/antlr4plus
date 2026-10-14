package com.antlr4plus.util

import com.intellij.openapi.diagnostic.ControlFlowException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** Propagate IntelliJ control flow, including through reflection and future invocation wrappers. */
fun rethrowIfControlFlow(failure: Throwable) {
    rethrowSpecialFailure(failure, includeFatalOrCancellation = false)
}

internal fun rethrowFatalOrCancellation(failure: Throwable) {
    rethrowSpecialFailure(failure, includeFatalOrCancellation = true)
}

private fun rethrowSpecialFailure(failure: Throwable, includeFatalOrCancellation: Boolean) {
    var current = failure
    var visited: MutableSet<Throwable>? = null
    while (true) {
        if (current is ControlFlowException) throw current
        if (
            includeFatalOrCancellation &&
                (current is CancellationException ||
                    current is VirtualMachineError ||
                    current.javaClass.name == "java.lang.ThreadDeath")
        )
            throw current

        // Ordinary exceptions can retain earlier failures as context. Only these transport
        // wrappers mean that the cause itself is the failure of the invoked operation.
        val cause =
            when (current) {
                is InvocationTargetException,
                is UndeclaredThrowableException,
                is ExecutionException,
                is CompletionException -> current.cause
                else -> null
            } ?: return
        val seen = visited ?: Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>()).also { visited = it }
        if (!seen.add(current)) return
        current = cause
    }
}
