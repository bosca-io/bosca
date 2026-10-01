package bosca.ai.kit.agents

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.structure.StructuredResponse
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONNull
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.TypeToken
import ai.koog.serialization.annotations.InternalKoogSerializationApi
import ai.koog.serialization.kotlinx.KotlinxSerializer
import bosca.ai.chat.model.ChatSessionStatus
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.image.ImageServices
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.ai.kit.agents.script.ScriptServices
import bosca.ai.kit.agents.pipeline.PipelineServices
import bosca.ai.kit.agents.session.BoscaChatMemoryProvider
import bosca.ai.kit.agents.session.BoscaPersistenceStorageProvider
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.tools.sql.SqlQuery
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.graphql.GraphQLService
import bosca.serialization.JsonConverter.asJsonElement
import bosca.serialization.UUID
import kotlinx.serialization.json.Json

/**
 * Builds **Kit** as a GOAP planner agent: a [KitPlannerStrategy] sequences Kit's actions over the typed
 * [KitState] to satisfy the goal. Identity is supplied at run time via `KitToolContext`. The platform
 * [json] (with its registered serializers) is threaded to everything that needs structured I/O — and
 * is wrapped as the agent config serializer so the planner can snapshot [KitState] (the `@Contextual`
 * UUID, BibleChapter, and Content all resolve through the platform serializers module).
 *
 * When a [sessionService] is supplied (run with `agent.run(request, sessionId)`, so `runId == sessionId`):
 * the Koog **Persistence** feature checkpoints the session so it can be restored — the large
 * `AgentCheckpointData` goes to object storage, only the latest-checkpoint pointer to the chat session
 * — and an **EventHandler** records Kit's final [KitResponse] to chat history so Studio's UI re-renders
 * (it subscribes to the session's messages). Internal sub-agent/tool turns are not recorded.
 */
class KitAgent(
    private val bibleService: BibleService,
    private val metadataService: MetadataService,
    private val documentService: DocumentService,
    private val collectionService: CollectionService,
    private val sqlQuery: SqlQuery,
    private val scriptServices: ScriptServices,
    private val imageServices: ImageServices,
    private val graphQLService: GraphQLService,
    private val promptExecutor: PromptExecutor,
    private val models: KitModels,
    private val json: KitJson,
    private val sessionService: KitSessionService,
    private val chatHistory: ChatHistoryService,
    private val analyticsServices: AnalyticsServices? = null,
    private val pipelineServices: PipelineServices? = null,
) {

    val agent: AIAgent<KitRequest, KitResponse> = AIAgent(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("kit") {

            },
            model = models.default,
            maxAgentIterations = 200,
            serializer = KitSerializer(KotlinxSerializer(json.json)),
        ),
        strategy = KitPlannerStrategy(
            promptExecutor,
            models,
            bibleService,
            metadataService,
            documentService,
            collectionService,
            sessionService,
            sqlQuery,
            analyticsServices,
            scriptServices,
            imageServices,
            graphQLService,
            json,
            pipelineServices,
        ).strategy,
        toolRegistry = ToolRegistry { },
        installFeatures = {
            install(Persistence) { storage = BoscaPersistenceStorageProvider(sessionService) }
            install(ChatMemory) { chatHistoryProvider = BoscaChatMemoryProvider(sessionService) }
            // Kit's final, user-facing turn → chat history (Studio re-renders from it). Sub-agents
            // run in their own agents, so onAgentCompleted fires once here with the resolved result.
            install(EventHandler) {
                onAgentStarting { ctx ->
                    val id = UUID.parse(ctx.runId)
                    chatHistory.setProcessing(id, true)
                    chatHistory.dispatchStatus(id, ChatSessionStatus.STREAMING)
                }
                onStrategyStarting { ctx ->
                    val id = UUID.parse(ctx.context.runId)
                    val storage = sessionService.getStorage(id)
                    ctx.context.storage.putAllSerialized(storage)
                }
                onAgentExecutionFailed { ctx ->
                    val id = UUID.parse(ctx.runId)
                    sessionService.setStorage(id, ctx.context.storage.toSerializedMap())
                    chatHistory.setProcessing(id, false)
                    chatHistory.dispatchStatus(id, ChatSessionStatus.FAILED)
                }
                onAgentCompleted { ctx ->
                    val id = UUID.parse(ctx.runId)
                    (ctx.result as? KitResponse)?.let { sessionService.recordResponse(id, it) }
                    sessionService.setStorage(id, ctx.context.storage.toSerializedMap())
                    chatHistory.setProcessing(id, false)
                    chatHistory.dispatchStatus(id, ChatSessionStatus.COMPLETED)
                }
            }
        },
    )
}
