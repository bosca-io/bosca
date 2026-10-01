package bosca.ai.kit.configuration

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import bosca.ai.agents.service.AgentService
import bosca.ai.agents.service.AgentToolService
import bosca.ai.chat.service.ChatDispatcher
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.KitModels
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.ai.kit.agents.image.ImageServices
import bosca.ai.kit.agents.image.KitImageClient
import bosca.ai.kit.agents.koogJson
import bosca.ai.kit.agents.script.ScriptServices
import bosca.ai.kit.agents.pipeline.PipelineServices
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.chat.KitChatDispatcher
import bosca.ai.kit.tools.sql.SqlQuery
import bosca.analytics.security.AnalyticsDashboardPermissionEvaluator
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsDashboardService
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
import bosca.analytics.service.AnalyticsVisualizationService
import bosca.scripting.engine.Engine
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.content.collection.service.CollectionService
import bosca.content.image.service.ImageService
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.ConnectionPool
import bosca.db.migrations.Migration
import bosca.graphql.GraphQLService
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.lock.DistributedLockFactory
import bosca.profile.profile.service.ProfileService
import bosca.pipelines.service.PipelineService
import bosca.security.service.GroupEvaluator
import bosca.server.BoscaApplication
import bosca.storage.service.ObjectStorageService
import kotlinx.serialization.json.Json

@Providers
class Configuration {

    @Provider(singleton = true)
    fun json(json: Json) = KitJson(koogJson(json))

    @Provider(name = "kit-migrations")
    fun migration(): Migration = KitMigration()

    /**
     * Kit requires native structured output throughout its agent graph. Keep those capabilities
     * explicit because the provider supports them even when Koog's model catalog lags behind. Keep the
     * definition local so construction is independent of Koog's provider and model catalog initialization.
     */
    @Provider(singleton = true)
    fun kitModels() = KitModels(
        default = LLModel(
            provider = LLMProvider(id = "openai", display = "OpenAI"),
            id = KIT_DEFAULT_MODEL_ID,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Speculation,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Vision.Image,
                LLMCapability.Document,
                LLMCapability.MultipleChoices,
                LLMCapability.OpenAIEndpoint.Responses,
                LLMCapability.Thinking,
                LLMCapability.Schema.JSON.Basic,
                LLMCapability.Schema.JSON.Standard,
            ),
            contextLength = 400_000,
            maxOutputTokens = 128_000,
        )
    )

    @Provider(singleton = true)
    fun sqlQuery(
        @ProviderName("trino-readonly")
        connectionPool: ConnectionPool
    ): SqlQuery = SqlQuery(connectionPool)

    @Provider(singleton = true)
    fun analyticsServices(
        queryService: AnalyticsQueryService,
        queryExecutionService: AnalyticsQueryExecutionService,
        visualizationService: AnalyticsVisualizationService,
        dashboardService: AnalyticsDashboardService,
        queryPermissionEvaluator: AnalyticsQueryPermissionEvaluator,
        visualizationPermissionEvaluator: AnalyticsVisualizationPermissionEvaluator,
        dashboardPermissionEvaluator: AnalyticsDashboardPermissionEvaluator,
        groupEvaluator: GroupEvaluator,
        json: Json,
    ) = AnalyticsServices(
        queryService = queryService,
        queryExecutionService = queryExecutionService,
        visualizationService = visualizationService,
        dashboardService = dashboardService,
        queryPermissionEvaluator = queryPermissionEvaluator,
        visualizationPermissionEvaluator = visualizationPermissionEvaluator,
        dashboardPermissionEvaluator = dashboardPermissionEvaluator,
        groupEvaluator = groupEvaluator,
        json = json,
    )

    /**
     * The Gemini image client + configured model for Kit's image sub-agent. Reads the service-account
     * JSON and model id from app config; when the account is absent the client is left unconfigured and
     * the image tools fail gracefully (the platform still starts without image credentials).
     */
    @Provider(singleton = true)
    fun kitImageClient(application: BoscaApplication): KitImageClient {
        val config = application.environment.config
        val account = config.propertyOrNull("google.genai.account")?.getString()
        val model = config.propertyOrNull("google.genai.image.model")?.getString() ?: DEFAULT_IMAGE_MODEL
        return KitImageClient(account, model)
    }

    @Provider(singleton = true)
    fun imageServices(
        imageClient: KitImageClient,
        metadataService: MetadataService,
        objectStorageService: ObjectStorageService,
        groupEvaluator: GroupEvaluator,
        collectionService: CollectionService,
        documentService: DocumentService,
        dataService: DataService,
        documentTemplateService: DocumentTemplateService,
        dataTemplateService: DataTemplateService,
        collectionTemplateService: CollectionTemplateService,
        metadataPermissionEvaluator: MetadataPermissionEvaluator,
        collectionPermissionEvaluator: CollectionPermissionEvaluator,
        imageService: ImageService,
    ): ImageServices = ImageServices(
        imageClient = imageClient,
        metadataService = metadataService,
        objectStorageService = objectStorageService,
        groupEvaluator = groupEvaluator,
        collectionService = collectionService,
        documentService = documentService,
        dataService = dataService,
        documentTemplateService = documentTemplateService,
        dataTemplateService = dataTemplateService,
        collectionTemplateService = collectionTemplateService,
        metadataPermissionEvaluator = metadataPermissionEvaluator,
        collectionPermissionEvaluator = collectionPermissionEvaluator,
        imageService = imageService,
    )

    @Provider(singleton = true)
    fun scriptServices(
        scriptService: ScriptService,
        scriptExecutionService: ScriptExecutionService,
        engine: Engine,
        agentToolService: AgentToolService,
        agentService: AgentService,
    ): ScriptServices = ScriptServices(
        scriptService = scriptService,
        scriptExecutionService = scriptExecutionService,
        engine = engine,
        agentToolService = agentToolService,
        agentService = agentService,
    )

    /** Pipeline contracts plus the caller-aware GraphQL boundary used by Kit's authoring tools. */
    @Provider(singleton = true)
    fun pipelineServices(
        pipelineService: PipelineService,
        graphQLService: GraphQLService,
        groupEvaluator: GroupEvaluator,
        json: KitJson,
    ): PipelineServices = PipelineServices(pipelineService, graphQLService, groupEvaluator, json.json)

    @Provider(singleton = true, name = "kit-chat-dispatcher")
    fun chatDispatcher(
        profileService: ProfileService,
        bibleService: BibleService,
        metadataService: MetadataService,
        documentService: DocumentService,
        collectionService: CollectionService,
        sqlQuery: SqlQuery,
        analyticsServices: AnalyticsServices,
        scriptServices: ScriptServices,
        imageServices: ImageServices,
        graphQLService: GraphQLService,
        pipelineServices: PipelineServices,
        promptExecutor: PromptExecutor,
        models: KitModels,
        json: KitJson,
        distributedLock: DistributedLockFactory,
        sessionService: KitSessionService,
        chatHistory: ChatHistoryService,
    ): ChatDispatcher = KitChatDispatcher(
        profileService = profileService,
        bibleService = bibleService,
        metadataService = metadataService,
        documentService = documentService,
        collectionService = collectionService,
        sqlQuery = sqlQuery,
        analyticsServices = analyticsServices,
        scriptServices = scriptServices,
        imageServices = imageServices,
        graphQLService = graphQLService,
        pipelineServices = pipelineServices,
        promptExecutor = promptExecutor,
        models = models,
        json = json,
        distributedLock = distributedLock,
        sessionService = sessionService,
        chatHistory = chatHistory
    )

    private companion object {
        const val KIT_DEFAULT_MODEL_ID = "gpt-5.3-codex"

        /** Default Gemini image-generation model when `google.genai.image.model` isn't configured. */
        const val DEFAULT_IMAGE_MODEL = "gemini-3.1-flash-image-preview"
    }
}
