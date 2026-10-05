package bosca.ai.kit.agents.readingtime

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
import kotlinx.serialization.json.Json

/**
 * Kit's reading-time specialist: estimates a document's word count and reading time, returning a
 * structured [ReadingTimeResponse]. A single structured decision (no tools), mirroring
 * [bosca.ai.kit.agents.routing.RouteAgent]. The owning action extracts the document text and folds the
 * result into [bosca.ai.kit.agents.KitResponse.ReadingTime].
 */
class ReadingTimeAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    json: KitJson,
    sessionService: KitSessionService?,
    promptDefinition: Prompt? = null,
) : KitSubAgent<ReadingTimeRequest, ReadingTimeResponse>() {

    /** Configured `prompt.metadata.reading.time` system prompt when provided; otherwise the built-in default. */
    private val systemPrompt = promptDefinition?.systemPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT

    /** Configured user-prompt template (`{document}` placeholder); when absent the built-in rendering is used. */
    private val userPromptTemplate = promptDefinition?.userPrompt?.takeIf { it.isNotBlank() }

    private val config = kitStructuredConfig(ReadingTimeResponse.serializer(), promptExecutor, model, json)

    override val service: GraphAIAgentService<ReadingTimeRequest, ReadingTimeResponse> = AIAgentService(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("reading_time") {
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

    private fun strategy(): AIAgentGraphStrategy<ReadingTimeRequest, ReadingTimeResponse> = strategy("reading_time") {
        val decide by nodeLLMRequestStructured(config = config)
        val plan by node<Result<StructuredResponse<ReadingTimeResponse>>, ReadingTimeResponse> { it.getOrThrow().data }
        edge(nodeStart forwardTo decide transformed { request -> renderUserMessage(request) })
        edge(decide forwardTo plan)
        edge(plan forwardTo nodeFinish)
    }

    /** Fill the configured user-prompt template's `{document}` placeholder, or fall back to the built-in rendering. */
    private fun renderUserMessage(request: ReadingTimeRequest): String =
        userPromptTemplate?.replace(DOCUMENT_PLACEHOLDER, request.text) ?: "Text:\n\n${request.text}"

    private companion object {
        private const val DOCUMENT_PLACEHOLDER = "{document}"
        private val DEFAULT_SYSTEM_PROMPT = """
            You estimate the reading time of text. For the text provided, compute the total word count and the
            reading time in minutes, assuming an average reading speed of 200 words per minute (round the
            minutes to the nearest whole number). Report only those two numbers.
        """.trimIndent()
    }
}
