package bosca.workops.model.spec

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@DbMapper(SpecContextTypeMapper::class)
@Serializable
enum class SpecContextType {
    GIT_RESOURCE,
    METADATA,
    COLLECTION,
    PROFILE,
    CHAT_CHANNEL,
    AI_SESSION,
    SPEC,
    TASK,
    PROJECT,
    EXTERNAL_URI,
}

object SpecContextTypeMapper : EnumMapper<SpecContextType>({ SpecContextType.valueOf(it.uppercase()) })

@BatchKey("id")
@Serializable
data class SpecContext(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("spec_id")
    @Contextual
    val specId: UUID,
    @ColumnName("context_type")
    val contextType: SpecContextType,
    @ColumnName("target_id")
    val targetId: String,
    val label: String? = null,
    val attributes: JsonElement? = null,
    @ColumnName("added_by_profile_id")
    @Contextual
    val addedByProfileId: UUID,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

@Serializable
data class CreateSpecContextInput(
    val contextType: SpecContextType,
    val targetId: String,
    val label: String? = null,
    val attributes: JsonElement? = null,
)
