package com.antlr4plus.previewService

import com.antlr4plus.state.ProcessingStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSessionLifecycleTest {
    @Test
    fun `late loading update cannot reopen a finished generation`() {
        val lifecycle = PreviewSessionLifecycle()
        val generation = lifecycle.begin().processingId
        val loading = lifecycle.progress(generation)
        val terminal = lifecycle.finish(generation, ProcessingStatus.Success)

        assertNotNull(loading)
        assertNotNull(terminal)
        assertFalse(lifecycle.shouldDeliver(loading!!))
        assertTrue(lifecycle.shouldDeliver(terminal!!.statusUpdate))
        assertNull(lifecycle.progress(generation))
    }

    @Test
    fun `terminal generation cannot be revised by a late outcome`() {
        val lifecycle = PreviewSessionLifecycle()
        val generation = lifecycle.begin().processingId

        assertNotNull(lifecycle.finish(generation, ProcessingStatus.Success))
        assertNull(lifecycle.finish(generation, ProcessingStatus.Failed))
        assertEquals(ProcessingStatus.Success, lifecycle.snapshot().status)
        assertFalse(lifecycle.isActive(generation))
    }

    @Test
    fun `superseded producer cannot publish or finish current generation`() {
        val lifecycle = PreviewSessionLifecycle()
        val oldGeneration = lifecycle.begin().processingId
        val currentGeneration = lifecycle.begin().processingId

        assertNull(lifecycle.progress(oldGeneration))
        assertNull(lifecycle.finish(oldGeneration, ProcessingStatus.Failed))
        assertNotNull(lifecycle.progress(currentGeneration))
        assertEquals(ProcessingStatus.Loading, lifecycle.snapshot().status)
    }

    @Test
    fun `invalidation atomically advances generation and closes progress`() {
        val lifecycle = PreviewSessionLifecycle()
        val generation = lifecycle.begin().processingId
        val loading = lifecycle.progress(generation)!!

        val invalidation = lifecycle.invalidate()

        assertEquals(generation, invalidation.invalidatedProcessingId)
        assertEquals(generation + 1L, invalidation.snapshot.processingId)
        assertEquals(ProcessingStatus.Cancelled, invalidation.snapshot.status)
        assertFalse(lifecycle.shouldDeliver(loading))
        assertTrue(lifecycle.shouldDeliver(invalidation.statusUpdate))
    }

    @Test
    fun `invalidation retains an already completed status by default`() {
        val lifecycle = PreviewSessionLifecycle()
        val generation = lifecycle.begin().processingId
        lifecycle.finish(generation, ProcessingStatus.Success)

        val invalidation = lifecycle.invalidate()

        assertEquals(ProcessingStatus.Success, invalidation.snapshot.status)
    }

    @Test
    fun `dependency wait and resume stay within one generation`() {
        val lifecycle = PreviewSessionLifecycle()
        val generation = lifecycle.begin().processingId

        assertTrue(lifecycle.moveTo(generation, ProcessingStatus.WaitingForVerification))
        assertEquals(ProcessingStatus.WaitingForVerification, lifecycle.snapshot().status)
        assertTrue(lifecycle.moveTo(generation, ProcessingStatus.Loading))
        assertEquals(ProcessingStatus.Loading, lifecycle.snapshot().status)

        lifecycle.begin()
        assertFalse(lifecycle.moveTo(generation, ProcessingStatus.WaitingForVerification))
    }

    @Test
    fun `concurrent finish and replacement have exactly one current owner`() {
        val lifecycle = PreviewSessionLifecycle()
        val generation = lifecycle.begin().processingId
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val finish =
                executor.submit<PreviewSessionLifecycle.TerminalTransition?> {
                    ready.countDown()
                    start.await()
                    lifecycle.finish(generation, ProcessingStatus.Success)
                }
            val replacement =
                executor.submit<PreviewSessionLifecycle.Start> {
                    ready.countDown()
                    start.await()
                    lifecycle.begin()
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val terminalUpdate = finish.get(5, TimeUnit.SECONDS)
            val replacementGeneration = replacement.get(5, TimeUnit.SECONDS).processingId
            assertEquals(generation + 1L, replacementGeneration)
            assertEquals(replacementGeneration, lifecycle.currentProcessingId())
            if (terminalUpdate != null) {
                assertFalse(lifecycle.shouldDeliver(terminalUpdate.statusUpdate))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `completion action belongs only to the generation that requested it`() {
        val lifecycle = PreviewSessionLifecycle()
        val runtimeGeneration = lifecycle.begin(PreviewSessionLifecycle.CompletionAction.SHOW_PROFILER).processingId
        val invalidation = lifecycle.invalidate()
        val ordinaryGeneration = lifecycle.begin().processingId

        assertEquals(
            PreviewSessionLifecycle.CompletionAction.SHOW_PROFILER,
            invalidation.completionAction,
        )
        assertNull(lifecycle.finish(runtimeGeneration, ProcessingStatus.Success))
        val completion = lifecycle.finish(ordinaryGeneration, ProcessingStatus.Success)!!
        assertEquals(PreviewSessionLifecycle.CompletionAction.NONE, completion.completionAction)
    }

    @Test
    fun `replacement reports the completion action it superseded`() {
        val lifecycle = PreviewSessionLifecycle()
        val first = lifecycle.begin(PreviewSessionLifecycle.CompletionAction.SHOW_PROFILER)

        val replacement = lifecycle.begin()

        assertEquals(first.processingId, replacement.supersededProcessingId)
        assertEquals(
            PreviewSessionLifecycle.CompletionAction.SHOW_PROFILER,
            replacement.supersededCompletionAction,
        )
    }
}
