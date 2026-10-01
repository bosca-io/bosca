package bosca.community.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CommunityActivity(
    val id: UUID,
    val groupId: UUID,
    val name: String,
    val description: String,
    val type: String,
    @Contextual
    val content: JsonElement?,
    @Contextual
    val schedule: JsonElement?
)
