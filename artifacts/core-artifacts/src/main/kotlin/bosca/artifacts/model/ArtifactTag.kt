package bosca.artifacts.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * A mutable pointer from a human-readable name to a specific manifest digest
 * within a Docker repository. Tags like `latest` or `v1.0` can be moved to
 * point at different manifests over time, unlike digest references which are immutable.
 */
@Serializable
data class ArtifactTag(
    val id: UUID,
    @ColumnName("repository_id")
    val repositoryId: UUID,
    val name: String,
    @ColumnName("manifest_digest")
    val manifestDigest: String,
    @Contextual
    val created: OffsetDateTime? = null,
    @Contextual
    val modified: OffsetDateTime? = null,
) {
    init {
        require(name.isNotBlank()) { "Tag name must not be blank" }
        require(manifestDigest.isNotBlank()) { "Manifest digest must not be blank" }
    }
}
