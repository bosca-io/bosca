package bosca.artifacts.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** Latest desired image for a destination/tag, with the result of its remote copy. */
@Serializable
data class ArtifactSync(
    @Contextual val id: UUID,
    @Contextual @ColumnName("destination_id") val destinationId: UUID,
    @Contextual @ColumnName("version_id") val versionId: UUID,
    @ColumnName("tag_name") val tagName: String,
    @ColumnName("manifest_digest") val manifestDigest: String,
    val attempts: Int = 0,
    @Contextual val synced: OffsetDateTime? = null,
    val error: String? = null,
    @Contextual val created: OffsetDateTime? = null,
    @Contextual val modified: OffsetDateTime? = null,
)
