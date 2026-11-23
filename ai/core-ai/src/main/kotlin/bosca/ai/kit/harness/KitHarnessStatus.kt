package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Public conversation lifecycle; contains no runtime credentials or infrastructure identity. */
@Serializable
data class KitHarnessStatus(
    val state: KitRuntimeSessionState,
    val cancellationRequested: Boolean,
    val failure: String? = null,
)
