package bosca.profile.rating.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class ProfileRating(
    val id: Long = 0,
    @Contextual
    @ColumnName("profile_id")
    val profileId: UUID,
    @Contextual
    @ColumnName("collection_id")
    val collectionId: UUID? = null,
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID? = null,
    @ColumnName("metadata_version")
    val metadataVersion: Int? = null,
    val rating: Int,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now()
)
