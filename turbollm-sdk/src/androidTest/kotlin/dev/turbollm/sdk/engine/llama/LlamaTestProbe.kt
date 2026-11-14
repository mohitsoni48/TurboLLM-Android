package dev.turbollm.sdk.engine.llama

/**
 * Native counters that exist only in a Debug build of the library (`test_probes.cpp`, compiled with
 * `TURBOLLM_TEST_PROBES`), so device tests can see what the production calls cannot report.
 */
internal object LlamaTestProbe {
    /** How many native llama sessions exist right now, counting down only once a session's model is freed. */
    external fun liveSessions(): Int
}
