package bosca.content.collection.model

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

/**
 * Core abstraction for a content collection that combines permissioning, workflow
 * lifecycle, content item identity, and search indexability.
 *
 * Extends [PermissibleEntity] for access control, [ContentItem] for workflow event
 * participation, [Indexable] to control search engine indexing, and [Recommendable]
 * to control recommendation eligibility.
 * Concrete implementations include [Collection] (full entity) and
 * [CollectionLanguageVariant] (language-specific variant).
 */
interface ICollection : PermissibleEntity<UUID>, ContentItem, Indexable, Recommendable {

    /** The display name of this collection. */
    val name: String

    /** The timestamp until which the current workflow state is valid, or `null` if indefinite. */
    val workflowStateValid: OffsetDateTime?

    /** The workflow ID to execute when this collection is deleted, or `null` if no delete workflow is configured. */
    val deleteWorkflowId: String?
}

@BatchKey(type = CollectionCacheKeyId::class)
@Serializable
data class CollectionLanguageVariant(
    @Contextual
    override val id: UUID,
    @ColumnName("language_tag")
    override val languageTag: String,
    override val name: String,
    val description: String? = null,
    @Contextual
    override val attributes: JsonElement? = null,
    @Contextual
    override val ready: OffsetDateTime? = null,
    @ColumnName("workflow_state_id")
    override val workflowStateId: String = "pending",
    @ColumnName("workflow_state_pending_id")
    override val workflowStatePendingId: String? = null,
    @ColumnName("workflow_state_valid")
    override val workflowStateValid: OffsetDateTime? = null,
    @ColumnName("delete_workflow_id")
    override val deleteWorkflowId: String? = null,
    override val public: Boolean = false,
    @ColumnName("public_list")
    override val publicList: Boolean = false,
    @ColumnName("public_supplementary")
    override val publicSupplementary: Boolean = false,
    val searchable: Boolean = true,
    val recommendable: Boolean = true,
) : ICollection {

    @Transient
    override val publicContent: Boolean = false

    @Transient
    override val version: Int? = null

    @Transient
    @Contextual
    override var itemAttributes: JsonElement? = null

    @Transient
    override val isPublished: Boolean = workflowStateId == "published"

    @Transient
    override val isAdvertised: Boolean = workflowStateId == "advertised"

    @Transient
    override val isDeleted: Boolean = false

    override val isSearchable: Boolean
        get() = searchable

    override val isRecommendable: Boolean
        get() = recommendable
}

@BatchKey(type = CollectionCacheKeyId::class)
@Serializable
data class Collection(
    @Contextual
    override val id: UUID = UUID.NIL,
    override val name: String,
    @ColumnName("language_tag")
    override val languageTag: String,
    val type: CollectionType = CollectionType.STANDARD,
    val description: String? = null,
    @Contextual
    override val attributes: JsonElement? = null,
    @ColumnName("system_attributes")
    @Contextual
    val systemAttributes: JsonElement? = null,
    val labels: List<String> = emptyList(),
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    override val ready: OffsetDateTime? = null,
    val etag: String? = null,
    val enabled: Boolean = true,
    @Contextual
    val ordering: JsonElement? = null,
    @ColumnName("workflow_state_id")
    override val workflowStateId: String,
    @ColumnName("workflow_state_pending_id")
    override val workflowStatePendingId: String? = null,
    @ColumnName("workflow_state_valid")
    override val workflowStateValid: OffsetDateTime? = null,
    @ColumnName("delete_workflow_id")
    override val deleteWorkflowId: String? = null,
    override val public: Boolean = false,
    @ColumnName("public_list")
    override val publicList: Boolean = false,
    @ColumnName("public_supplementary")
    override val publicSupplementary: Boolean = false,
    val locked: Boolean = false,
    @ColumnName("items_locked")
    val itemsLocked: Boolean = false,
    val deleted: Boolean = false,
    @ColumnName("template_metadata_id")
    val templateMetadataId: UUID? = null,
    @ColumnName("template_metadata_version")
    val templateMetadataVersion: Int? = null,
    val searchable: Boolean = true,
    val recommendable: Boolean = true,
    @ColumnName("recommendation_contexts")
    val recommendationContexts: List<String> = emptyList(),
) : ICollection {

    @Transient
    var defaultLanguageVariant: CollectionLanguageVariant? = null

    @Transient
    override val publicContent: Boolean = false

    @Transient
    override val version: Int? = null

    @Transient
    @Contextual
    override var itemAttributes: JsonElement? = null

    @Transient
    override val isPublished: Boolean = workflowStateId == "published"

    @Transient
    override val isAdvertised: Boolean = workflowStateId == "advertised"

    @Transient
    override val isDeleted: Boolean = deleted

    override val isSearchable: Boolean
        get() = searchable

    override val isRecommendable: Boolean
        get() = recommendable
}
