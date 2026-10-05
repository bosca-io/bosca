package bosca.profile.guide.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProfileGuideHistory(
    val id: Long = 0,
    @Contextual
    @ColumnName("profile_id")
    val profileId: UUID,
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID,
    val version: Int,
    @Contextual
    val attributes: JsonElement,
    @Contextual
    val completed: OffsetDateTime? = null
)