package bosca.content.timeevent.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TimeEventInput(
    val type: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long? = null,
    val sort: Int? = null,
    @Contextual
    val attributes: JsonElement? = null
)
