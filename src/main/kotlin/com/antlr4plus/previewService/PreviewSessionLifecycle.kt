package com.antlr4plus.previewService

import com.antlr4plus.state.ProcessingStatus

/**
 * Thread-safe authority for preview request ownership, phase, and progress ordering.
 *
 * The processing generation is the structural axis for all asynchronous preview work. A caller must still own the
 * current generation before it may publish progress or commit a terminal state.
 */
internal class PreviewSessionLifecycle {
    enum class CompletionAction {
        NONE,
        SHOW_PROFILER,
    }

    data class Snapshot(
        val processingId: Long,
        val status: ProcessingStatus,
        val completionAction: CompletionAction,
    )

    data class StatusUpdate(
        val processingId: Long,
        val sequence: Long,
        val isLoading: Boolean,
    )

    data class Start(
        val processingId: Long,
        val supersededProcessingId: Long,
        val supersededCompletionAction: CompletionAction,
    )

    data class Invalidation(
        val invalidatedProcessingId: Long,
        val snapshot: Snapshot,
        val statusUpdate: StatusUpdate,
        val completionAction: CompletionAction,
    )

    data class TerminalTransition(
        val statusUpdate: StatusUpdate,
        val completionAction: CompletionAction,
    )

    private val lock = Any()

    @Volatile
    private var current =
        Snapshot(
            processingId = 0L,
            status = ProcessingStatus.Idle,
            completionAction = CompletionAction.NONE,
        )
    private var latestStatusSequence = 0L

    fun snapshot(): Snapshot = current

    fun currentProcessingId(): Long = current.processingId

    fun isCurrent(processingId: Long): Boolean = current.processingId == processingId

    fun isActive(processingId: Long): Boolean {
        val snapshot = current
        return snapshot.processingId == processingId && snapshot.status.isActive()
    }

    /** Starts a fresh loading generation and invalidates every prior producer. */
    fun begin(completionAction: CompletionAction = CompletionAction.NONE): Start =
        synchronized(lock) {
            val supersededProcessingId = current.processingId
            val supersededCompletionAction = current.completionAction
            val nextId = supersededProcessingId + 1L
            current = Snapshot(nextId, ProcessingStatus.Loading, completionAction)
            latestStatusSequence = 0L
            Start(nextId, supersededProcessingId, supersededCompletionAction)
        }

    /**
     * Invalidates the current generation and immediately closes its progress stream. If no explicit status is supplied,
     * active work becomes cancelled while completed state is retained until another selection starts.
     */
    fun invalidate(status: ProcessingStatus? = null): Invalidation =
        synchronized(lock) {
            val invalidatedId = current.processingId
            val invalidatedCompletionAction = current.completionAction
            val nextStatus =
                status
                    ?: if (current.status.isActive()) {
                        ProcessingStatus.Cancelled
                    } else {
                        current.status
                    }
            val nextId = invalidatedId + 1L
            current = Snapshot(nextId, nextStatus, CompletionAction.NONE)
            latestStatusSequence = 1L
            Invalidation(
                invalidatedProcessingId = invalidatedId,
                snapshot = current,
                statusUpdate = StatusUpdate(nextId, latestStatusSequence, isLoading = false),
                completionAction = invalidatedCompletionAction,
            )
        }

    /** Publishes a progress update only for the current, active generation. */
    fun progress(processingId: Long): StatusUpdate? =
        synchronized(lock) {
            if (current.processingId != processingId || !current.status.isActive()) return null
            StatusUpdate(processingId, ++latestStatusSequence, isLoading = true)
        }

    /** Moves the current generation between active phases without changing its ownership. */
    fun moveTo(processingId: Long, status: ProcessingStatus): Boolean =
        synchronized(lock) {
            require(status.isActive()) { "moveTo requires an active preview status" }
            if (current.processingId != processingId || !current.status.isActive()) return false
            current = current.copy(status = status)
            true
        }

    /** Commits a terminal phase and closes progress as one ordered transition. */
    fun finish(processingId: Long, status: ProcessingStatus): TerminalTransition? =
        synchronized(lock) {
            require(!status.isActive()) { "finish requires a terminal preview status" }
            if (current.processingId != processingId || !current.status.isActive()) return null

            val completionAction = current.completionAction
            current = Snapshot(processingId, status, CompletionAction.NONE)
            TerminalTransition(
                statusUpdate = StatusUpdate(processingId, ++latestStatusSequence, isLoading = false),
                completionAction = completionAction,
            )
        }

    /** Rechecks ownership and ordering when an update eventually reaches the EDT. */
    fun shouldDeliver(update: StatusUpdate): Boolean =
        synchronized(lock) {
            update.processingId == current.processingId &&
                update.sequence == latestStatusSequence &&
                (!update.isLoading || current.status.isActive())
        }

    private fun ProcessingStatus.isActive(): Boolean =
        this == ProcessingStatus.Loading || this == ProcessingStatus.WaitingForVerification
}
