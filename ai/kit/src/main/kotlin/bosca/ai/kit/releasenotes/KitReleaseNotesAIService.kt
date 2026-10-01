package bosca.ai.kit.releasenotes

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
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.release.LocalizedReleaseNotes
import bosca.workops.model.release.ReleaseNotesGenerationInput
import bosca.workops.service.ReleaseNotesAIService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Kit's direct, non-chat release-notes capability used by WorkOps release automation. */
@ServiceImplementation
class KitReleaseNotesAIService(
    private val promptExecutor: PromptExecutor,
    private val models: KitModels,
    private val json: KitJson,
) : ReleaseNotesAIService {

    override suspend fun generate(input: ReleaseNotesGenerationInput): LocalizedReleaseNotes =
        ReleaseNotesAgent(promptExecutor, models.write, json)
            .run(input, UUID.random().toString())
            .notes
}

@Serializable
private data class ReleaseNotesResponse(val notes: LocalizedReleaseNotes)

/** A one-shot structured Kit specialist that drafts only the source-language store copy. */
private class ReleaseNotesAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    private val json: KitJson,
) : KitSubAgent<ReleaseNotesGenerationInput, ReleaseNotesResponse>() {

    private val responseConfig = kitStructuredConfig(ReleaseNotesResponse.serializer(), promptExecutor, model, json)

    override val service: GraphAIAgentService<ReleaseNotesGenerationInput, ReleaseNotesResponse> = AIAgentService(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("release_notes") { system(SYSTEM_PROMPT) },
            model = model,
            maxAgentIterations = 5,
            serializer = KitSerializer(KotlinxSerializer(json.json)),
        ),
        strategy = strategy(),
        toolRegistry = ToolRegistry { },
    )

    private fun strategy(): AIAgentGraphStrategy<ReleaseNotesGenerationInput, ReleaseNotesResponse> =
        strategy("release_notes") {
            val draft by nodeLLMRequestStructured(config = responseConfig)
            val result by node<Result<StructuredResponse<ReleaseNotesResponse>>, ReleaseNotesResponse> {
                it.getOrThrow().data
            }
            edge(nodeStart forwardTo draft transformed { request -> json.json.encodeToString(request) })
            edge(draft forwardTo result)
            edge(result forwardTo nodeFinish)
        }

    private companion object {
        val SYSTEM_PROMPT = """
            You are Kit's release-notes specialist. Draft customer-facing store notes from the supplied
            Git commit and diff evidence. Return one notes object whose locale exactly matches sourceLocale.
            It has three non-empty fields:
            - playReleaseNotes: concise Google Play "What's new" copy (maximum 500 Unicode characters).
            - appStoreWhatsNew: concise App Store "What's New" copy (maximum 4,000 characters).
            - testFlightWhatToTest: concrete TestFlight testing guidance tied to the changes.

            Mention only behavior supported by the evidence; omit internal task keys, commit hashes,
            implementation jargon, and unsupported claims. Use plain product language and short paragraphs
            or bullets. Do not invent changes to fill an empty category. Localization is handled separately.
        """.trimIndent()
    }
}
