package bosca.configuration.model

import bosca.security.model.PermissibleEntity
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Configuration(
    @Contextual
    override val id: UUID = UUID.NIL,
    val key: String,
    val description: String,
    override val public: Boolean,
) : PermissibleEntity<UUID> {

    @Transient
    override val publicList: Boolean = false

    @Transient
    override val publicContent: Boolean = false

    @Transient
    override val publicSupplementary: Boolean = false

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = false
}