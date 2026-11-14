package dev.turbollm.sdk.internal

import dev.turbollm.sdk.EngineId
import java.io.File

/**
 * Faults QA can switch on in a debuggable app, so the host's "engine unavailable" and "SDK did not start" paths
 * can be driven live. Written with `adb shell run-as <package>`, which Android allows only for debuggable apps;
 * a release build never reads the file.
 */
internal data class DebugFaults(val unavailable: Set<EngineId>, val noServer: Boolean) {

    /** [realProbe], unless this engine is faulted: then it always reports the debug reason. */
    fun probeFor(engine: EngineId, realProbe: () -> String?): () -> String? =
        if (engine in unavailable) { { DISABLED_REASON } } else realProbe

    companion object {
        val NONE = DebugFaults(unavailable = emptySet(), noServer = false)

        /** One directive per line: `unavailable=<engine id>` or `no-server`. Anything else is ignored. */
        fun parse(text: String): DebugFaults {
            val directives = text.lineSequence()
                .map(String::trim)
                .filterNot { it.isEmpty() || it.startsWith("#") }
                .toList()
            val unavailable = directives.mapNotNull { it.removePrefixOrNull(UNAVAILABLE) }.map(::EngineId).toSet()
            return DebugFaults(unavailable, noServer = directives.any { it == NO_SERVER })
        }

        /** Reads `<dir>/faults` in a debuggable app; [NONE] otherwise, or when it is absent or unreadable. */
        fun read(dir: File, debuggable: Boolean): DebugFaults {
            if (!debuggable) return NONE
            return try {
                File(dir, FAULTS_FILE).takeIf { it.isFile }?.readText()?.let(::parse) ?: NONE
            } catch (_: Exception) {
                NONE
            }
        }

        private fun String.removePrefixOrNull(prefix: String): String? =
            if (startsWith(prefix)) removePrefix(prefix).trim() else null

        private const val FAULTS_FILE = "faults"
        private const val UNAVAILABLE = "unavailable="
        private const val NO_SERVER = "no-server"
        private const val DISABLED_REASON = "Disabled for testing (debug build)."
    }
}
