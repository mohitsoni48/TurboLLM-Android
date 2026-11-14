package dev.turbollm.sdk.service

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.turbollm.sdk.EngineState
import dev.turbollm.sdk.TurboLLM
import dev.turbollm.sdk.TurboLLMConfig
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TurboLLMServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun closeTheCore() {
        TurboLLMService.stop(context)
        TurboLLM.currentOrNull()?.close()
        TurboLLMService.defaultConfig = null
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun theServiceHoldsTheCoreAndLeavesItAliveWhenItStops() = runBlocking {
        TurboLLMService.defaultConfig = TurboLLMConfig(modelRoot = File(context.filesDir, "turbollm-service-test"))

        TurboLLMService.start(context)
        val core = awaitCore()
        TurboLLMService.stop(context)
        awaitServiceStopped()

        assertSame(core, TurboLLM.currentOrNull())
        assertEquals(EngineState.Idle, core.state.value)
    }

    private suspend fun awaitCore(): TurboLLM = withTimeout(WAIT_MS) {
        var core = TurboLLM.currentOrNull()
        while (core == null) {
            delay(POLL_MS)
            core = TurboLLM.currentOrNull()
        }
        core
    }

    private suspend fun awaitServiceStopped() = withTimeout(WAIT_MS) {
        while (serviceRuns()) delay(POLL_MS)
    }

    @Suppress("DEPRECATION")
    private fun serviceRuns(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == TurboLLMService::class.java.name }

    private companion object {
        const val TEST_TIMEOUT_MS = 30_000L
        const val WAIT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
