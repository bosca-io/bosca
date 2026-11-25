package bosca.ai.kit.harness

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** A private object reservation, retained until object deletion succeeds. Bytes include pending uploads. */
@Serializable
data class KitRuntimeArchive(
    val key: String,
    @Contextual @ColumnName("session_id") val sessionId: UUID,
    val generation: Long,
    val bytes: Long,
    @Contextual val created: OffsetDateTime? = OffsetDateTime.now(),
)
