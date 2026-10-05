package bosca.communications.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Channel(
    @Contextual
    val id: UUID,
    val key: String,
    val name: String,
    @Contextual
    val configuration: JsonElement
)