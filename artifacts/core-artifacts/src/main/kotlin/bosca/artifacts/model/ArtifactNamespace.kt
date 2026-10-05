package bosca.artifacts.model

import bosca.security.model.PermissibleEntity
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.OffsetDateTime

/**
 * A namespace groups artifact repositories and controls access at a broad level.
 *
 * Examples: `library` for Docker Hub-style images, `com.acme` for Maven group IDs,
 * `@acme` for npm scoped packages. Namespaces are the primary unit for commercial
 * licensing — an API token scoped to a namespace grants access to all repositories
 * within it.
 */
@Serializable
data class ArtifactNamespace(
    override val id: UUID,
    val name: String,
    /** Whether anonymous pull access is allowed for repositories in this namespace. */
    override val public: Boolean = false,
    @Contextual
    val created: OffsetDateTime? = null,
) : PermissibleEntity<UUID> {

    init {
        require(':' !in name) { "Namespace name must not contain colons (scope delimiter): '$name'" }
        require('/' !in name) { "Namespace name must not contain forward slashes (path delimiter): '$name'" }
        require(name.isNotBlank()) { "Namespace name must not be blank" }
    }

    @Transient
    override val publicContent: Boolean = public

    @Transient
    override val publicList: Boolean = true

    @Transient
    override val publicSupplementary: Boolean = false

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = false
}
