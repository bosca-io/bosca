package bosca.profile.bookmark.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.OffsetDateTime

@Serializable
data class ProfileBookmark(
    val id: Long = 0,
    @Contextual
    @ColumnName("profile_id")
    val profileId: UUID,
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID? = null,
    @ColumnName("metadata_version")
    val metadataVersion: Int? = null,
    @Contextual
    @ColumnName("collection_id")
    val collectionId: UUID? = null,
    @Contextual
    val attributes: JsonElement? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now()
)