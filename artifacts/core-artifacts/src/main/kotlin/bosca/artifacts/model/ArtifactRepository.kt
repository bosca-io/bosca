package bosca.artifacts.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * A named artifact within a namespace, serving a specific protocol.
 *
 * For Docker, this corresponds to a repository name (e.g., `nginx`).
 * For Maven, this is `groupId:artifactId` (e.g., `com.acme:sdk`).
 * For npm, this is the package name (e.g., `@acme/cli`).
 */
@Serializable
data class ArtifactRepository(
    val id: UUID,
    @ColumnName("namespace_id")
    val namespaceId: UUID,
    val name: String,
    val type: String,
    @Contextual
    val created: OffsetDateTime? = null,
    @Contextual
    val modified: OffsetDateTime? = null,
) {
    init {
        require(':' !in name) { "Repository name must not contain colons (scope delimiter): '$name'" }
        require('/' !in name) { "Repository name must not contain forward slashes (path delimiter): '$name'" }
        require(name.isNotBlank()) { "Repository name must not be blank" }
    }

    /** The [ArtifactType] enum for this repository's protocol, parsed from the stored [type] string. */
    val artifactType: ArtifactType get() = ArtifactType.fromValue(type)
}
