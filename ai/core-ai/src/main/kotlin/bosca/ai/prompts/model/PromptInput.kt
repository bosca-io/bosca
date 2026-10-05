@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.ai.prompts.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PromptInput(
    val key: String,
    val name: String,
    val description: String,
    val inputType: String,
    val outputType: String,
    @Contextual
    val schema: JsonElement?,
    val systemPrompt: String,
    val userPrompt: String,
    @Contextual
    val gitRepositoryId: kotlin.uuid.Uuid? = null,
    val gitPath: String? = null
)
