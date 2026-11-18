package bosca.ai.kit.harness

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Ordered intent, recorded before any externally effectful native prompt. */
@Serializable
data class KitRuntimeTurn(
    @Contextual val id: UUID,
    @Contextual @ColumnName("session_id") val sessionId: UUID,
    val sequence: Long = 0,
    val message: JsonElement,
    val status: String = "QUEUED",
    val result: JsonElement? = null,
    @ColumnName("resolved_settings") val resolvedSettings: JsonElement? = null,
    @ColumnName("context_manifest") val contextManifest: JsonElement? = null,
    /** Fixed when the prompt is marked sent; queueing and setup do not consume this budget. */
    @Contextual val deadline: OffsetDateTime? = null,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
)
