package bosca.profile.mark.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProfileMark(
    val id: Long = 0,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID? = null,
    @ColumnName("metadata_version")
    val metadataVersion: Int? = null,
    @ColumnName("collection_id")
    @Contextual
    val collectionId: UUID? = null,
    @Contextual
    val attributes: JsonElement? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now()
)