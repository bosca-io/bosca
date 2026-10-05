package bosca.ai.models.model

import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

data class Model(
    val id: UUID = UUID.NIL,
    val key: String,
    val type: String,
    val name: String,
    val description: String,
    val configuration: JsonElement? = null,
) {

    fun toLLMModel(): LLModel {
        return when {
            type.startsWith("google.") -> {
                when (val name = type.substring("google.".length)) {
                    "Gemini2_5Pro" -> GoogleModels.Gemini2_5Pro
                    "Gemini2_5Flash" -> GoogleModels.Gemini2_5Flash
                    "Gemini2_0Flash001" -> GoogleModels.Gemini2_0FlashLite001
                    "Gemini2_5FlashLite" -> GoogleModels.Gemini2_5FlashLite
                    "Gemini3_Pro_Preview",
                    "Gemini3_1Pro_Preview" -> GoogleModels.Gemini3_1Pro_Preview
                    else -> error("Unsupported model: $name")
                }
            }

            type.startsWith("openai.chat.") -> {
                when (val name = type.substring("openai.chat.".length)) {
                    "GPT4o" -> OpenAIModels.Chat.GPT4o
                    "GPT4oMini" -> OpenAIModels.Chat.GPT4oMini
                    "GPT4_1" -> OpenAIModels.Chat.GPT4_1
                    "GPT4_1Mini" -> OpenAIModels.Chat.GPT4_1Mini
                    "GPT5" -> OpenAIModels.Chat.GPT5
                    "GPT5Mini" -> OpenAIModels.Chat.GPT5Mini
                    "GPT5_1" -> OpenAIModels.Chat.GPT5_1
                    "GPT5_1Codex" -> OpenAIModels.Chat.GPT5_1Codex
                    "GPT5_2" -> OpenAIModels.Chat.GPT5_2
                    "GPT5_2Pro" -> OpenAIModels.Chat.GPT5_2Pro
                    else -> error("Unsupported model: $name")
                }
            }

            else -> error("Unsupported model type: $type")
        }
    }
}
