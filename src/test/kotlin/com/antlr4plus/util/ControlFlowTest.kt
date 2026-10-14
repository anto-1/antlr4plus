package com.antlr4plus.util

import com.intellij.openapi.diagnostic.ControlFlowException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import junit.framework.TestCase

class ControlFlowTest : TestCase() {

    fun `test IntelliJ control flow exception is rethrown unchanged`() {
        val expected = TestControlFlowException()
        val actual =
            try {
                rethrowIfControlFlow(expected)
                fail("Control-flow exception was swallowed")
                null
            } catch (failure: TestControlFlowException) {
                failure
            }
        assertSame(expected, actual)
    }

    fun `test ordinary failure is left for the caller to handle`() {
        rethrowIfControlFlow(IllegalStateException("ordinary failure"))
    }

    fun testTransportWrappersPreserveOriginalCancellationAndFatalFailure() {
        for (expected in
            listOf(TestControlFlowException(), CancellationException("cancelled"), TestFatalError(), ThreadDeath())) {
            val wrappers =
                listOf(
                    InvocationTargetException(expected),
                    UndeclaredThrowableException(expected),
                    ExecutionException(expected),
                    CompletionException(expected),
                    CompletionException(
                        ExecutionException(InvocationTargetException(UndeclaredThrowableException(expected))),
                    ),
                )
            for (wrapper in wrappers) assertPropagates(expected) { rethrowFatalOrCancellation(wrapper) }
        }
    }

    fun testMarkerCheckFindsControlFlowInsideNestedInvocationWrappers() {
        val expected = TestControlFlowException()
        assertPropagates(expected) {
            rethrowIfControlFlow(ExecutionException(InvocationTargetException(expected)))
        }
        val markedWrapper = MarkedInvocation(IllegalStateException("ordinary cause"))
        assertPropagates(markedWrapper) { rethrowFatalOrCancellation(markedWrapper) }
    }

    fun testOrdinaryFailuresAndOpaqueDiagnosticCausesRemainRecoverable() {
        val ordinary =
            listOf(
                NoSuchMethodException("optional method"),
                IllegalStateException("ordinary failure"),
                LinkageError("missing optional dependency"),
                IllegalStateException("operation failed", CancellationException("earlier cancellation")),
            )
        for (failure in ordinary) {
            rethrowFatalOrCancellation(failure)
            rethrowFatalOrCancellation(InvocationTargetException(failure))
            rethrowIfControlFlow(failure)
        }
    }

    fun testCyclicTransportCausesDoNotLoopAndDeepChainsDoNotRecurse() {
        rethrowFatalOrCancellation(CyclicInvocation())
        val expected = CancellationException("deep cancellation")
        var wrapped: Throwable = expected
        repeat(2000) { wrapped = InvocationTargetException(wrapped) }
        assertPropagates(expected) { rethrowFatalOrCancellation(wrapped) }
    }

    private fun assertPropagates(expected: Throwable, action: () -> Unit) {
        val actual =
            try {
                action()
                null
            } catch (failure: Throwable) {
                failure
            }
        assertSame(expected, actual)
    }

    private class TestFatalError : VirtualMachineError()

    private class MarkedInvocation(cause: Throwable) : InvocationTargetException(cause), ControlFlowException

    private class CyclicInvocation : InvocationTargetException() {
        override val cause: Throwable
            get() = this
    }

    private class TestControlFlowException : RuntimeException(), ControlFlowException
}
