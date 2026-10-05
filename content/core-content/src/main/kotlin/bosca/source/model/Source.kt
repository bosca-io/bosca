package bosca.source.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Source(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String,
    @Contextual
    val configuration: JsonElement
)
