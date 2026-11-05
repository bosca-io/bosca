package bosca.ai.kit.harness

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A native permission request with a separate, durable decision deadline. */
@Serializable
data class KitRuntimeApproval(
    @Contextual val id: UUID,
    @Contextual @ColumnName("session_id") val sessionId: UUID,
    @Contextual @ColumnName("turn_id") val turnId: UUID,
    @ColumnName("request_id") val requestId: String,
    val request: JsonElement,
    val decision: String? = null,
    @Contextual val expires: OffsetDateTime,
    @Contextual val created: OffsetDateTime? = OffsetDateTime.now(),
)
