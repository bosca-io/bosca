package bosca.community.model

import bosca.security.model.PermissibleEntity
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

@Serializable
data class CommunityGroup(
    @Contextual
    override val id: UUID,
    val name: String,
    val description: String,
    val type: CommunityGroupType,
    val visibility: CommunityVisibility,
    val attributes: JsonElement? = null,
) : PermissibleEntity<UUID> {

    @Transient
    override val public: Boolean = visibility == CommunityVisibility.PUBLIC

    @Transient
    override val publicContent: Boolean = visibility == CommunityVisibility.PUBLIC

    @Transient
    override val publicList: Boolean = visibility == CommunityVisibility.PUBLIC

    @Transient
    override val publicSupplementary: Boolean = false

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = true

    @Transient
    override val isDeleted: Boolean = false
}
