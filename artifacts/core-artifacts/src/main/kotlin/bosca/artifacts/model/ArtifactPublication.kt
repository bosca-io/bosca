package bosca.artifacts.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** Immutable stored bytes selected when publication is requested after the uploads finish. */
@Serializable
data class ArtifactPublicationFile(val filename: String, val digest: String, val size: Long, val mediaType: String)

/** One durable publication per destination/version, with independent publication and verification results. */
@Serializable
data class ArtifactPublication(
    @Contextual val id: UUID,
    @Contextual @ColumnName("destination_id") val destinationId: UUID,
    @Contextual @ColumnName("version_id") val versionId: UUID,
    @ColumnName("tag_name") val tagName: String,
    @ColumnName("commit_sha") val commitSha: String,
    val prerelease: Boolean,
    @property:DbMapper(JsonbMapper::class) val files: List<ArtifactPublicationFile>,
    @ColumnName("release_id") val releaseId: Long? = null,
    val attempts: Int = 0,
    @Contextual val published: OffsetDateTime? = null,
    @Contextual val verified: OffsetDateTime? = null,
    val error: String? = null,
    @Contextual val created: OffsetDateTime? = null,
    @Contextual val modified: OffsetDateTime? = null,
)
