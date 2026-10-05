package bosca.scripting.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ScriptInput(
    val key: String,
    val name: String,
    val description: String = "",
    val type: ScriptType = ScriptType.GENERAL,
    val source: String,
    val public: Boolean = false,
    @Contextual
    val inputSchema: JsonElement? = null,
    @Contextual
    val outputSchema: JsonElement? = null,
    @Contextual
    val configuration: JsonElement? = null
) {
    init {
        require(key.isNotBlank()) { "Script key must not be blank" }
        require(name.isNotBlank()) { "Script name must not be blank" }
    }
}
