package bosca.profile.model

import bosca.content.collection.model.ContentItem
import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.graphql.annotations.BatchKey
import bosca.search.Indexable
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

@DbMapper(ProfileTypeMapper::class)
@Serializable
enum class ProfileType {
    GENERIC,
    ORGANIZATION,
    CHILD
}

object ProfileTypeMapper : EnumMapper<ProfileType>({ ProfileType.valueOf(it.uppercase()) })

@BatchKey("id")
@Serializable
data class Profile(
    @Contextual
    override val id: UUID = UUID.NIL,
    val type: ProfileType,
    @Contextual
    val principal: UUID? = null,
    @ColumnName("collection_id")
    val collectionId: UUID? = null,
    val name: String,
    val visibility: ProfileVisibility,
    /** Whether this profile may appear in profile search when its visibility also permits it. */
    val searchable: Boolean = true,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    /**
     * Non-null once an admin has *marked the profile deleted* — a reversible staging step ahead of
     * a full hard delete. While set, the profile is treated as deleted and is excluded from the
     * search index on its next reindex regardless of [searchable] (see [isDeleted] / [isSearchable]).
     */
    @ColumnName("deleted_at")
    @Contextual
    val deletedAt: OffsetDateTime? = null
) : PermissibleEntity<UUID>, Indexable, ContentItem {

    @Transient
    override val version: Int? = null

    @Transient
    override val languageTag: String? = null

    @Transient
    override val attributes: JsonElement? = null

    @Transient
    override val itemAttributes: JsonElement? = null

    @Transient
    override val workflowStateId: String = "published"

    @Transient
    override val workflowStatePendingId: String? = null

    @Transient
    override val ready: OffsetDateTime? = null

    @Transient
    override val public: Boolean = visibility == ProfileVisibility.PUBLIC

    @Transient
    override val publicContent: Boolean = false

    @Transient
    override val publicList: Boolean = false

    @Transient
    override val publicSupplementary: Boolean = false

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = deletedAt != null

    @Transient
    override val isSearchable: Boolean = searchable && deletedAt == null

    @Transient
    val isPrimary: Boolean = false
}
