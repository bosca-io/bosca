package bosca.ai.kit.localization

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredResponse
import ai.koog.serialization.kotlinx.KotlinxSerializer
import bosca.ai.kit.agents.KitModels
import bosca.ai.kit.agents.KitSerializer
import bosca.ai.kit.agents.KitSubAgent
import bosca.ai.kit.agents.strategy.kitStructuredConfig
import bosca.ai.kit.configuration.KitJson
import bosca.localization.model.LocalizationAITranslationRequest
import bosca.localization.model.LocalizationAITranslationResult
import bosca.localization.service.LocalizationAIService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Kit implementation of localization's generic, read-only AI translation seam. */
@ServiceImplementation
class KitLocalizationAIService(
    private val promptExecutor: PromptExecutor,
    private val models: KitModels,
    private val json: KitJson,
) : LocalizationAIService {

    override suspend fun translate(request: LocalizationAITranslationRequest): List<LocalizationAITranslationResult> =
        LocalizationTranslationAgent(promptExecutor, models.write, json)
            .run(request, UUID.random().toString())
            .translations
            .map { LocalizationAITranslationResult(UUID.parse(it.stringId), it.languageTag, it.text) }
}

@Serializable
private data class LocalizationTranslationResponse(
    val translations: List<LocalizationTranslationAgentResult>,
)

@Serializable
private data class LocalizationTranslationAgentResult(
    val stringId: String,
    val languageTag: String,
    val text: String,
)

/** One structured translation pass across the requested strings and target languages. */
private class LocalizationTranslationAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    private val json: KitJson,
) : KitSubAgent<LocalizationAITranslationRequest, LocalizationTranslationResponse>() {

    private val responseConfig = kitStructuredConfig(
        LocalizationTranslationResponse.serializer(), promptExecutor, model, json,
    )

    override val service: GraphAIAgentService<LocalizationAITranslationRequest, LocalizationTranslationResponse> =
        AIAgentService(
            promptExecutor = promptExecutor,
            agentConfig = AIAgentConfig(
                prompt = prompt("localization_translation") { system(SYSTEM_PROMPT) },
                model = model,
                maxAgentIterations = 5,
                serializer = KitSerializer(KotlinxSerializer(json.json)),
            ),
            strategy = strategy(),
            toolRegistry = ToolRegistry { },
        )

    private fun strategy(): AIAgentGraphStrategy<LocalizationAITranslationRequest, LocalizationTranslationResponse> =
        strategy("localization_translation") {
            val translate by nodeLLMRequestStructured(config = responseConfig)
            val result by node<Result<StructuredResponse<LocalizationTranslationResponse>>, LocalizationTranslationResponse> {
                it.getOrThrow().data
            }
            edge(nodeStart forwardTo translate transformed { request -> json.json.encodeToString(request) })
            edge(translate forwardTo result)
            edge(result forwardTo nodeFinish)
        }

    private companion object {
        val SYSTEM_PROMPT = """
            You translate product strings for Bosca's localization service. Return exactly one translation
            for every supplied stringId and target languageTag pair; copy both identifiers exactly. Translate
            from sourceLanguageTag into each target language naturally, using key and context to resolve intent.
            Preserve placeholders, markup, punctuation structure, and intentional line breaks exactly. Obey
            maxLength when present. Return only translated text—never commentary or additional entries.
        """.trimIndent()
    }
}
