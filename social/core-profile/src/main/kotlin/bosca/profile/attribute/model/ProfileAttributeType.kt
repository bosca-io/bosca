package bosca.profile.attribute.model

import bosca.db.annotation.ColumnName
import bosca.profile.model.ProfileVisibility
import bosca.security.model.PermissibleEntity
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ProfileAttributeType(
    override val id: String,
    val description: String,
    val name: String,
    val visibility: ProfileVisibility,
    val protected: Boolean,
    @Contextual
    @ColumnName("form_schema_id")
    val formSchemaId: UUID? = null
) : PermissibleEntity<String> {

    @Transient
    override val publicList: Boolean = false

    @Transient
    override val publicContent: Boolean = false

    @Transient
    override val publicSupplementary: Boolean = false

    override val isPublished: Boolean
        get() = true

    override val isAdvertised: Boolean
        get() = false

    override val public: Boolean
        get() = true

    override val isDeleted: Boolean
        get() = false
}