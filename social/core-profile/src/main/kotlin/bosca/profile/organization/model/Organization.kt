package bosca.profile.organization.model


import bosca.content.collection.model.ContentItem
import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.profile.model.ProfileVisibility
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

@BatchKey("id")
@Serializable
data class Organization(
    @Contextual
    override val id: UUID = UUID.NIL,
    val name: String,
    @Contextual
    override val attributes: JsonElement,
    @Contextual
    @ColumnName("system_attributes")
    val systemAttributes: JsonElement,
    val visibility: ProfileVisibility,
    @ColumnName("profile_id")
    val profileId: UUID,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now()
) : PermissibleEntity<UUID>, ContentItem {

    @Transient
    override val version: Int? = null

    @Transient
    override val languageTag: String? = null

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
    override val isDeleted: Boolean = false
}
