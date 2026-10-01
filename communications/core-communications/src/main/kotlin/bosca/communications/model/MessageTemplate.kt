package bosca.communications.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MessageTemplate(
    val id: UUID,
    val key: String,
    val title: String,
    val attributes: JsonElement
)