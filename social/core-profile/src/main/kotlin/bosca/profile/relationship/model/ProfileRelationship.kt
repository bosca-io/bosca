package bosca.profile.relationship.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProfileRelationship(
    @ColumnName("profile_id_1")
    @Contextual
    val profileId1: UUID,
    @ColumnName("profile_id_2")
    @Contextual
    val profileId2: UUID,
    @ColumnName("type")
    val type: String,
    val attributes: JsonElement? = null,
)
