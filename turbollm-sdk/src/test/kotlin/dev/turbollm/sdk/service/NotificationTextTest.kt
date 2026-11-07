package dev.turbollm.sdk.service

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineId
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.EngineState
import dev.turbollm.sdk.LoadedModel
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.ModelRef
import dev.turbollm.sdk.api.TurboLLMException
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {

    @Test
    fun anIdleCoreReadsIdle() {
        assertEquals("Idle", notificationText(EngineState.Idle))
    }

    @Test
    fun aReadyCoreNamesItsModel() {
        val ready = EngineState.Ready(qwenModel, busy = false, queued = 0)

        assertEquals("Model ready · qwen3-0.6b-q4_k_m", notificationText(ready))
    }

    @Test
    fun aBusyCoreStillReadsReadyWithItsModel() {
        val busy = EngineState.Ready(qwenModel, busy = true, queued = 2)

        assertEquals("Model ready · qwen3-0.6b-q4_k_m", notificationText(busy))
    }

    @Test
    fun aLoadingCoreSaysItIsLoading() {
        assertEquals("Loading a model", notificationText(EngineState.Loading(ModelRef.Installed("qwen"))))
    }

    @Test
    fun aFailedLoadSaysTheModelFailedToLoad() {
        val failed = EngineState.Failed(ModelRef.Installed("qwen"), TurboLLMException.ModelLoadFailed("bad file"))

        assertEquals("The model failed to load", notificationText(failed))
    }

    private val qwenModel = LoadedModel(
        id = "qwen3-0.6b-q4_k_m",
        path = "/models/qwen3-0.6b-q4_k_m.gguf",
        format = ModelFormat.GGUF,
        engine = EngineKind.LLAMA_CPP,
        contextSize = 4096,
        backend = Backend.CPU,
        notes = emptyList(),
        engineId = EngineId.LLAMA_CPP_CPU,
    )
}
