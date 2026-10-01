package bosca.ai.kit.chat

import ai.koog.prompt.executor.model.PromptExecutor
import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.service.ChatDispatcher
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.KitAgent
import bosca.ai.kit.agents.KitModels
import bosca.ai.kit.agents.KitRequest
import bosca.ai.kit.agents.KitRequestIdentity
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.ai.kit.agents.image.ImageServices
import bosca.ai.kit.agents.script.ScriptServices
import bosca.ai.kit.agents.pipeline.PipelineServices
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.tools.KitToolContext
import bosca.ai.kit.tools.sql.SqlQuery
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.graphql.GraphQLService
import bosca.lock.DistributedLockFactory
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class KitChatDispatcher(
    private val profileService: ProfileService,
    private val bibleService: BibleService,
    private val metadataService: MetadataService,
    private val documentService: DocumentService,
    private val collectionService: CollectionService,
    private val sqlQuery: SqlQuery,
    private val analyticsServices: AnalyticsServices,
    private val scriptServices: ScriptServices,
    private val imageServices: ImageServices,
    private val graphQLService: GraphQLService,
    private val promptExecutor: PromptExecutor,
    private val models: KitModels,
    private val json: KitJson,
    private val distributedLock: DistributedLockFactory,
    private val sessionService: KitSessionService,
    private val chatHistory: ChatHistoryService,
    private val pipelineServices: PipelineServices,
) : ChatDispatcher {

    private val agent = KitAgent(
        promptExecutor = promptExecutor,
        models = models,
        json = json,
        sessionService = sessionService,
        bibleService = bibleService,
        metadataService = metadataService,
        documentService = documentService,
        collectionService = collectionService,
        sqlQuery = sqlQuery,
        analyticsServices = analyticsServices,
        scriptServices = scriptServices,
        imageServices = imageServices,
        graphQLService = graphQLService,
        chatHistory = chatHistory,
        pipelineServices = pipelineServices,
    )

    override suspend fun dispatch(authenticationContext: AuthenticationContext, sessionId: UUID, message: ChatMessageInput) {
        // Persist the user's turn to chat history first, so it survives the UI's refresh (Studio renders
        // purely from history) and is ordered before Kit's reply — independent of the single-flight lock.
        sessionService.recordUserMessage(sessionId, message)
        withContext(KitToolContext(authenticationContext)) {
            // Single-flight per chat session: a second message for the same session waits for the in-flight
            // turn rather than running the shared agent re-entrantly (which would corrupt run state and race
            // the checkpoint index). The TTL frees the lock if the holder crashes mid-turn.
            val result = distributedLock.create("kit-chat-$sessionId").withLock(
                ttlMillis = LOCK_TTL_MS,
                waitTimeoutMillis = LOCK_WAIT_MS,
            ) {
                val response = agent.agent.run(
                    KitRequest(
                        identity = authenticationContext.principal()?.asPrincipal()?.let {
                            KitRequestIdentity(
                                principal = it,
                                profile = profileService.getPrimaryProfile(it)
                            )
                        },
                        message = message,
                    ),
                    sessionId = sessionId.toString(),
                )
                response
            }
            checkNotNull(result) { "Kit is still processing an earlier message for session $sessionId; please retry." }
        }
    }

    private companion object {
        /** How long the per-session lock is held before auto-expiring (deadlock guard if a turn crashes). */
        const val LOCK_TTL_MS = 600_000L

        /** How long a queued message waits to acquire the session before giving up. */
        const val LOCK_WAIT_MS = 600_000L
    }
}
