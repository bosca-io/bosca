package bosca.scheduler.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class JobDefinitionInfo(
    val id: String,
    val name: String,
    val displayName: String = "",
    val queueName: String,
    val parameterSchema: JsonElement? = null
)
