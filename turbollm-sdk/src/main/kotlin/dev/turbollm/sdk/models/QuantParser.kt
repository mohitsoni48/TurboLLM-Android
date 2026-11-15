package dev.turbollm.sdk.models

import dev.turbollm.sdk.ModelFormat
import java.util.Locale

/** litert-community precision names (`turbollm/src/models/litertlm.ts` `QUANT_RE`). */
private val LITERTLM_QUANT = Regex(
    """(?:^|[-_. ])(int[248]|fp16|f16|fp32|bf16|q[48])(?=$|[-_. ])""",
    RegexOption.IGNORE_CASE,
)

/**
 * The quant a file name states, upper-cased; null when it states none. Anchored on separators, and the last
 * match wins, because a model name can contain a number-letter run of its own.
 */
internal fun quantFromName(fileName: String, format: ModelFormat): String? = when (format) {
    // The GGUF rules live in the Rust core (D-K1), shared with the daemon.
    ModelFormat.GGUF -> dev.turbollm.core.quantFromName(fileName)
    ModelFormat.LITERTLM ->
        LITERTLM_QUANT.findAll(fileName).lastOrNull()?.groupValues?.get(1)?.uppercase(Locale.ROOT)
}
