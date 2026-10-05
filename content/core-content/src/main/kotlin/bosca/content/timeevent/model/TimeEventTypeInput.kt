package bosca.content.timeevent.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TimeEventTypeInput(
    val id: String,
    val name: String,
    val description: String,
    @Contextual
    val schema: JsonElement? = null,
    @Contextual
    val configuration: JsonElement? = null
)
