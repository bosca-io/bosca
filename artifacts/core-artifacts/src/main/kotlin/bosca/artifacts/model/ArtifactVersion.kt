package bosca.artifacts.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime

/**
 * A versioned release of an artifact, holding protocol-specific metadata.
 *
 * For Docker, versions correspond to manifest digests.
 * For Maven, versions are semver-style strings (e.g., `1.0.0`).
 * For npm, versions follow semver conventions.
 *
 * The [metadata] field stores protocol-specific information as JSONB:
 * - Docker: manifest media type, config digest
 * - Maven: POM coordinates, classifier, packaging
 * - npm: package.json contents
 */
@Serializable
data class ArtifactVersion(
    val id: UUID,
    @ColumnName("repository_id")
    val repositoryId: UUID,
    val version: String,
    @Contextual
    val metadata: JsonObject? = null,
    @Contextual
    val created: OffsetDateTime? = null,
) {
    init {
        require(version.isNotBlank()) { "Version string must not be blank" }
    }
}
