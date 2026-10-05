package bosca.profile.guide.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProfileGuideProgress(
    @Contextual
    @ColumnName("profile_id")
    val profileId: UUID,
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID,
    val version: Int,
    @Contextual
    val attributes: JsonElement?,
    @Contextual
    val started: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("completed_step_ids")
    val completedStepIds: List<Long> = emptyList(),
)