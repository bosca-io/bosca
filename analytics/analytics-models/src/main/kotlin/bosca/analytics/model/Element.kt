package bosca.analytics.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class Element(
    val id: String? = null,
    val type: String? = null,
    val content: List<Content>? = emptyList(),
    @Contextual
    val extras: JsonElement? = JsonNull
)