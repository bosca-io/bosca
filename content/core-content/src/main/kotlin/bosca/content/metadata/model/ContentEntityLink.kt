package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@DbMapper(ContentLinkTargetMapper::class)
@Serializable
enum class ContentLinkTarget {
    METADATA,
    COLLECTION,
    PROFILE,
    TASK,
    SPEC,
    REQUIREMENT,
    PROJECT,
    PROGRAM,
    GIT_REPOSITORY,
    CHAT_CHANNEL,
    AI_SESSION,
    EXTERNAL_URI,
}

object ContentLinkTargetMapper : EnumMapper<ContentLinkTarget>({ ContentLinkTarget.valueOf(it.uppercase()) })

@BatchKey("id")
@Serializable
data class ContentEntityLink(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    @ColumnName("metadata_version")
    val metadataVersion: Int,
    @ColumnName("target_type")
    val targetType: ContentLinkTarget,
    @ColumnName("target_id")
    val targetId: String,
    @ColumnName("node_type")
    val nodeType: String? = null,
    val position: JsonElement? = null,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

@Serializable
data class ContentEntityLinkInput(
    @Contextual
    val metadataId: UUID,
    val metadataVersion: Int,
    val targetType: ContentLinkTarget,
    val targetId: String,
    val nodeType: String? = null,
    val position: JsonElement? = null,
)
