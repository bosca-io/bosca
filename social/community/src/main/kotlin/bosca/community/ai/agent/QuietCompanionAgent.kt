package bosca.community.ai.agent

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.base.structure.OpenAIStandardJsonSchemaGenerator
import ai.koog.prompt.message.Message
import ai.koog.prompt.structure.json.JsonStructure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class QuietCompanionResponse(
    val shouldRespond: Boolean,
    val response: String? = null,
    val reason: String? = null
)

class QuietCompanionAgent(
    private val executor: PromptExecutor
) {

    private val responseStructure = JsonStructure.create<QuietCompanionResponse>(
        json = Json { ignoreUnknownKeys = true },
        schemaGenerator = OpenAIStandardJsonSchemaGenerator,
        examples = emptyList()
    )

    suspend fun processMessage(
        channelName: String,
        history: List<Message>,
        newMessage: Message.User
    ): QuietCompanionResponse? {
        val prompt = prompt("quiet-companion") {
            system(
                """
                You are a 'Quiet Companion', a gentle spiritual guide for a small faith-based group or family. Your role is to cultivate consistent, meaningful faith-based community.
                
                Guiding Principles:
                - Simplicity: Keep it simple.
                - Intimacy: You are in a small, intimate group (current group: $channelName).
                - Formation over consumption: Encourage participation more than providing content.
                - Support, not replacement: Assist leaders, don't replace them.
                - Family-first: Be appropriate for all ages.
                
                Role & Philosophy:
                - Never dominate conversation.
                - Act like a quiet co-leader.
                - Be context-aware and limited in frequency.
                - Use AI as a "gentle guide" to help groups stay connected without overwhelming them.
                
                Your task:
                Analyze the chat history and the new message. Decide if you should respond.
                Respond ONLY if:
                1. You are explicitly addressed.
                2. There is a clear opportunity to provide a gentle nudge, a conversation starter, or a prayer encouragement.
                3. You can offer a follow-up question, an encouragement prompt, or a scripture-based response that feels natural.
                
                If you decide not to respond, set shouldRespond to false.
                If you respond, keep it brief, supportive, and gentle.
                
                Output your decision in the specified JSON format.
                """.trimIndent()
            )
            history.forEach { 
                when (it) {
                    is Message.User -> user(it.textContent())
                    is Message.Assistant -> assistant(it.textContent())
                    else -> {}
                }
            }
            user(newMessage.textContent())
        }

        return try {
            val responses = executor.execute(
                prompt = prompt,
                model = OpenAIModels.Chat.GPT4o,
            )
            val content = responses.textContent()
            if (content.isNotBlank()) {
                val json = Json { ignoreUnknownKeys = true }
                // Sometimes LLMs wrap JSON in code blocks
                val cleanedContent = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                json.decodeFromString<QuietCompanionResponse>(cleanedContent)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
