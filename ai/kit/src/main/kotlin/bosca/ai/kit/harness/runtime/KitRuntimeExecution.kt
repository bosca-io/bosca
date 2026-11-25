package bosca.ai.kit.harness.runtime

import bosca.serialization.OffsetDateTime

/** Observed workload state, independent of an infrastructure controller's resource model. */
data class KitRuntimeExecution(
    val created: OffsetDateTime,
    val terminal: Boolean,
    val endpoint: String?,
    val image: String?,
    val imageId: String?,
    val failure: String? = null,
)
