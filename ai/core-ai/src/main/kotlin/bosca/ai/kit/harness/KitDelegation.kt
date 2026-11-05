package bosca.ai.kit.harness

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Durable parent/child identity, immutable reservation and eventual typed output. */
@Serializable
data class KitDelegation(
    @Contextual val id: UUID,
    @Contextual @ColumnName("parent_session_id") val parentSessionId: UUID,
    @Contextual @ColumnName("parent_turn_id") val parentTurnId: UUID,
    @Contextual @ColumnName("root_turn_id") val rootTurnId: UUID,
    @Contextual @ColumnName("child_session_id") val childSessionId: UUID,
    @Contextual @ColumnName("child_turn_id") val childTurnId: UUID,
    val depth: Int,
    @property:DbMapper(JsonbMapper::class) val input: KitDelegationInput,
    @property:DbMapper(JsonbMapper::class) val policy: KitDelegationPolicy,
    @property:DbMapper(JsonbMapper::class) val authority: KitRuntimeAuthority,
    @property:DbMapper(JsonbMapper::class) @ColumnName("context_manifest") val contextManifest: KitContextManifest? = null,
    val status: KitDelegationStatus = KitDelegationStatus.QUEUED,
    @property:DbMapper(JsonbMapper::class) val result: KitDelegationResult? = null,
    val failure: String? = null,
    val waiting: Boolean = false,
    @Contextual @ColumnName("wait_turn_id") val waitTurnId: UUID? = null,
    @Contextual val deadline: OffsetDateTime,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val modified: OffsetDateTime = OffsetDateTime.now(),
)
