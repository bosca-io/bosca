package bosca.content.metadata.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class GuideTemplateInput(
    @Contextual
    val configuration: JsonElement?,
    @Contextual
    val defaultAttributes: JsonElement?,
    val rrule: String,
    @Contextual
    val steps: List<GuideTemplateStepInput>,
    val type: GuideType
)