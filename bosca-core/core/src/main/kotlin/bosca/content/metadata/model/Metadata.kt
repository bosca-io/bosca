package bosca.content.metadata.model

import bosca.content.collection.model.ContentItem
import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.recommendations.Recommendable
import bosca.search.Indexable
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement


val EmptyMetadata = Metadata(
    id = UUID.NIL,
    name = "",
    type = MetadataType.STANDARD,
    contentType = "",
    contentLength = -1,
    languageTag = "",
    workflowStateId = "unknown",
)

@BatchKey(type = MetadataCacheKeyId::class)
@Serializable
data class Metadata(
    @Contextual
    override val id: UUID = UUID.NIL,
    override val version: Int = 1,
    @ColumnName("active_version")
    val activeVersion: Int = 1,
    @ColumnName("parent_id")
    @Contextual
    val parentId: UUID? = null,
    val name: String,
    val type: MetadataType,
    @ColumnName("content_type")
    val contentType: String,
    @ColumnName("content_length")
    val contentLength: Long?,
    @ColumnName("language_tag")
    override val languageTag: String,
    val labels: List<String> = emptyList(),
    @Contextual
    override val attributes: JsonElement? = null,
    @ColumnName("system_attributes")
    @Contextual
    val systemAttributes: JsonElement? = null,
    val deleted: Boolean = false,
    override val public: Boolean = false,
    @ColumnName("public_content")
    override val publicContent: Boolean = false,
    @ColumnName("public_supplementary")
    override val publicSupplementary: Boolean = false,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime? = null,
    @Contextual
    val uploaded: OffsetDateTime? = null,
    @Contextual
    override val ready: OffsetDateTime? = null,
    @ColumnName("workflow_state_id")
    override val workflowStateId: String,
    @ColumnName("workflow_state_pending_id")
    override val workflowStatePendingId: String? = null,
    @ColumnName("workflow_state_valid")
    @Contextual
    val workflowStateValid: OffsetDateTime? = null,
    @ColumnName("source_id")
    @Contextual
    val sourceId: UUID? = null,
    @ColumnName("source_identifier")
    val sourceIdentifier: String? = null,
    @ColumnName("source_url")
    val sourceUrl: String? = null,
    @ColumnName("source_status")
    val sourceStatus: SourceStatus? = null,
    @ColumnName("delete_workflow_id")
    val deleteWorkflowId: String? = null,
    @ColumnName("permission_mutation")
    val permissionMutation: Int = 0,
    val etag: String? = null,
    val locked: Boolean = false,
    @ColumnName("sync_variant_collections")
    val syncVariantCollections: Boolean = true,
    @ColumnName("sync_variant_relationships")
    val syncVariantRelationships: Boolean = true,
    val searchable: Boolean = true,
    val recommendable: Boolean = true,
    @ColumnName("comments_enabled")
    val commentsEnabled: Boolean = false,
    @ColumnName("comment_replies_enabled")
    val commentRepliesEnabled: Boolean = false,
    @ColumnName("recommendation_contexts")
    val recommendationContexts: List<String> = emptyList(),
) : PermissibleEntity<UUID>, Indexable, Recommendable, ContentItem {

    @Transient
    @Contextual
    override var itemAttributes: JsonElement? = null

    @Transient
    override val publicList: Boolean = false

    override val isPublished: Boolean
        get() = workflowStateId == "published"

    override val isAdvertised: Boolean
        get() = workflowStateId == "advertised"

    override val isDeleted: Boolean
        get() = deleted

    override val isSearchable: Boolean
        get() = searchable

    override val isRecommendable: Boolean
        get() = recommendable

}
