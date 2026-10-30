package dev.turbollm.sdk.chat

import dev.turbollm.sdk.api.TurboLLMException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore

/**
 * One generation at a time, with a bounded FIFO queue behind it. A request that finds the queue full is
 * refused with [TurboLLMException.ServerBusy] instead of waiting. Not reentrant.
 */
internal class GenerationGate(private val maxQueuedRequests: Int) {

    private val permit = Semaphore(1)
    private val waiters = AtomicInteger(0)

    val busy: Boolean get() = permit.availablePermits == 0

    val queued: Int get() = waiters.get()

    suspend fun <T> withPermit(generation: suspend () -> T): T {
        if (!permit.tryAcquire()) waitInQueue()
        try {
            return generation()
        } finally {
            permit.release()
        }
    }

    /** Waits for every generation ahead of the caller to finish. Never refused and never counted as queued. */
    suspend fun awaitIdle() {
        permit.acquire()
        permit.release()
    }

    private suspend fun waitInQueue() {
        if (waiters.incrementAndGet() > maxQueuedRequests) {
            waiters.decrementAndGet()
            throw TurboLLMException.ServerBusy()
        }
        try {
            permit.acquire()
        } finally {
            waiters.decrementAndGet()
        }
    }
}
