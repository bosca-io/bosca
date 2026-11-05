package bosca.ai.kit.harness

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Drives one durable session; redelivery reconciles before considering another prompt. */
@Serializable
data class KitRuntimeJob(@Contextual val sessionId: UUID) : bosca.queue.annotations.IJobDefinition
