package bosca.artifacts.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Associates a blob with an artifact version in a specific role.
 *
 * Roles identify what the blob represents within the version:
 * - Docker: `manifest`, `config`, `layer`
 * - Maven: `pom`, `jar`, `sources`, `javadoc`, `signature`
 * - npm: `tarball`
 */
@Serializable
data class ArtifactVersionBlob(
    @ColumnName("version_id")
    val versionId: UUID,
    val digest: String,
    val role: String,
    val filename: String? = null,
    @ColumnName("media_type")
    val mediaType: String? = null,
) {
    init {
        require(digest.isNotBlank()) { "Blob digest must not be blank" }
        require(role.isNotBlank()) { "Blob role must not be blank" }
    }
}
