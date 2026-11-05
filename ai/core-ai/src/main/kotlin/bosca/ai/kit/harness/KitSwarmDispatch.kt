package bosca.ai.kit.harness

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Durable Swarm intent; recovery materializes it only after admission commits. */
@Serializable
data class KitSwarmDispatch(@Contextual val id: UUID,
    @Contextual @ColumnName("session_id") val sessionId: UUID,
    val profile: String, val key: String,
    @property:DbMapper(JsonbMapper::class) val request: KitSwarmRequest?,
    val materialized: Boolean = false, val stopping: Boolean = false, val stopped: Boolean = false,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now())
