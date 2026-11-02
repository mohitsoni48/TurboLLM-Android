package dev.turbollm.sdk.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A host that subclasses the service declares only its subclass, so the helpers must start that one. */
@RunWith(AndroidJUnit4::class)
class TurboLLMServiceIntentTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = TEST_TIMEOUT_MS)
    fun theIntentNamesTheHostsSubclass() {
        val intent = TurboLLMService.serviceIntent(context, HostService::class.java)

        assertEquals(HostService::class.java.name, intent.component?.className)
    }

    class HostService : TurboLLMService()

    private companion object {
        const val TEST_TIMEOUT_MS = 10_000L
    }
}
