package bosca.ai.kit.agents.topics

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredResponse
import ai.koog.serialization.kotlinx.KotlinxSerializer
import bosca.ai.kit.agents.KitSerializer
import bosca.ai.kit.agents.KitSubAgent
import bosca.ai.kit.agents.session.BoscaChatMemoryProvider
import bosca.ai.kit.agents.session.BoscaPersistenceStorageProvider
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.agents.strategy.kitStructuredConfig
import bosca.ai.kit.configuration.KitJson
import bosca.ai.prompts.model.Prompt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Kit's topic-matching specialist: given a document's text and the platform's candidate topics, it
 * picks the relevant ones, returning a structured [TopicsResponse]. A single structured decision (no
 * tools), mirroring [bosca.ai.kit.agents.routing.RouteAgent]. The owning action gathers the candidate
 * topics + document text and resolves the matches back to real collections.
 */
class TopicsAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    private val json: KitJson,
    sessionService: KitSessionService?,
    promptDefinition: Prompt? = null,
) : KitSubAgent<TopicsRequest, TopicsResponse>() {

    /** Configured `prompt.metadata.topics` system prompt when provided; otherwise the built-in default. */
    private val systemPrompt = promptDefinition?.systemPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT

    /** Configured user-prompt template (`{document}`/`{topics}` placeholders); when absent the built-in rendering is used. */
    private val userPromptTemplate = promptDefinition?.userPrompt?.takeIf { it.isNotBlank() }

    private val config = kitStructuredConfig(TopicsResponse.serializer(), promptExecutor, model, json)

    override val service: GraphAIAgentService<TopicsRequest, TopicsResponse> = AIAgentService(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("topics") {
                system(systemPrompt)
            },
            model = model,
            maxAgentIterations = 5,
            serializer = KitSerializer(KotlinxSerializer(json.json)),
        ),
        strategy = strategy(),
        toolRegistry = ToolRegistry { },
        installFeatures = {
            sessionService?.let {
                install(Persistence) { storage = BoscaPersistenceStorageProvider(it) }
                install(ChatMemory) { chatHistoryProvider = BoscaChatMemoryProvider(sessionService) }
            }
        },
    )

    private fun strategy(): AIAgentGraphStrategy<TopicsRequest, TopicsResponse> = strategy("topics") {
        val decide by nodeLLMRequestStructured(config = config)
        val plan by node<Result<StructuredResponse<TopicsResponse>>, TopicsResponse> { it.getOrThrow().data }
        edge(nodeStart forwardTo decide transformed { request -> renderUserMessage(request) })
        edge(decide forwardTo plan)
        edge(plan forwardTo nodeFinish)
    }

    private fun renderUserMessage(request: TopicsRequest): String {
        val topicsJson = json.json.encodeToString(ListSerializer(AvailableTopic.serializer()), request.availableTopics)
        return userPromptTemplate
            ?.replace(DOCUMENT_PLACEHOLDER, request.text)
            ?.replace(TOPICS_PLACEHOLDER, topicsJson)
            ?: "Text:\n${request.text}\n\nAvailable Topics:\n$topicsJson"
    }

    private companion object {
        private const val DOCUMENT_PLACEHOLDER = "{document}"
        private const val TOPICS_PLACEHOLDER = "{topics}"
        private val DEFAULT_SYSTEM_PROMPT = """
            You are an AI assistant trained to analyze text and identify relevant topics from a predefined list
            of topics. Process the given text, extract meaningful topics that are represented or implied within
            it, and match them with the list of available topics provided in JSON.

            For each topic matched, include the topic's `id` and `name` exactly as given in the available topics.
            Only include topics that are reasonably relevant to the text — both explicit and implicit topics
            that are semantically close to an available topic. If none are relevant, return an empty list.
        """.trimIndent()
    }
}
