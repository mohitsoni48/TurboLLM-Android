package dev.turbollm.sdk.chat

import dev.turbollm.sdk.api.TurboLLMException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationGateTest {

    @Test
    fun anIdleGateIsNeitherBusyNorQueued() {
        val gate = GenerationGate(maxQueuedRequests = 8)

        assertFalse(gate.busy)
        assertEquals(0, gate.queued)
    }

    @Test
    fun waitersTakeThePermitInArrivalOrder() = runTest {
        val gate = GenerationGate(maxQueuedRequests = 8)
        val entered = mutableListOf<Int>()
        val holder = holdGate(gate)

        (1..3).forEach { waiter ->
            launch { gate.withPermit { entered += waiter } }
            runCurrent()
        }
        assertTrue(gate.busy)
        assertEquals(3, gate.queued)

        holder.release()
        runCurrent()

        assertEquals(listOf(1, 2, 3), entered)
        assertFalse(gate.busy)
        assertEquals(0, gate.queued)
    }

    @Test
    fun aWaiterBeyondTheQueueLimitIsRefusedAsServerBusy() = runTest {
        val gate = GenerationGate(maxQueuedRequests = 2)
        val holder = holdGate(gate)
        repeat(2) {
            launch { gate.withPermit { } }
            runCurrent()
        }

        val refusal = runCatching { gate.withPermit { } }.exceptionOrNull()

        assertTrue("got $refusal", refusal is TurboLLMException.ServerBusy)
        assertEquals(2, gate.queued)
        holder.release()
    }

    @Test
    fun aWaiterCancelledWhileQueuedLeavesTheQueueAndNeverRuns() = runTest {
        val gate = GenerationGate(maxQueuedRequests = 8)
        val holder = holdGate(gate)
        var cancelledWaiterRan = false
        val waiter = launch { gate.withPermit { cancelledWaiterRan = true } }
        runCurrent()
        assertEquals(1, gate.queued)

        waiter.cancel()
        runCurrent()
        assertEquals(0, gate.queued)

        holder.release()
        runCurrent()
        assertFalse(cancelledWaiterRan)
        assertFalse(gate.busy)
    }

    @Test
    fun thePermitIsReleasedWhenTheHolderThrows() = runTest {
        val gate = GenerationGate(maxQueuedRequests = 8)

        val failure = runCatching { gate.withPermit { error("engine blew up") } }.exceptionOrNull()

        assertEquals("engine blew up", failure?.message)
        assertFalse(gate.busy)
        assertEquals("next", gate.withPermit { "next" })
    }

    @Test
    fun aRequestThatFindsTheGateFreeIsNeverCountedAsQueued() = runTest {
        val gate = GenerationGate(maxQueuedRequests = 0)

        val queuedInside = gate.withPermit { gate.queued }

        assertEquals(0, queuedInside)
    }

    @Test
    fun awaitingIdleWaitsForTheHolderEvenWhenTheQueueIsFull() = runTest {
        val gate = GenerationGate(maxQueuedRequests = 0)
        val holder = holdGate(gate)

        val idle = async { gate.awaitIdle() }
        runCurrent()
        assertFalse(idle.isCompleted)
        assertEquals(0, gate.queued)

        holder.release()
        idle.await()
        assertFalse(gate.busy)
    }

    private class Holder(private val gateOpen: CompletableDeferred<Unit>) {
        fun release() {
            gateOpen.complete(Unit)
        }
    }

    private fun TestScope.holdGate(gate: GenerationGate): Holder {
        val gateOpen = CompletableDeferred<Unit>()
        launch { gate.withPermit { gateOpen.await() } }
        runCurrent()
        return Holder(gateOpen)
    }
}
