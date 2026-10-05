@file:OptIn(ExperimentalSerializationApi::class)

package bosca.server.configuration

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.livesessions.nats.NatsLiveSessions
import bosca.analytics.livesessions.redis.RedisLiveSessions
import bosca.analytics.repository.EventRepository
import bosca.analytics.service.NatsEventConsumer
import bosca.bible.BibleFactory
import bosca.bible.processor.BibleFactoryImpl
import bosca.category.service.CategoryService
import bosca.configuration.DocumentToTextProvider
import bosca.configuration.MetadataDocumentToTextProvider
import bosca.configuration.service.ConfigurationService
import bosca.content.collection.jobs.CollectionIndexExecutor
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.jobs.MetadataIndexExecutor
import bosca.content.metadata.model.LocaleAwareDocument
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import bosca.content.transformations.CollectionToSearchDocument
import bosca.content.transformations.CollectionToSearchDocumentConfiguration
import bosca.content.transformations.DefaultMetadataToSearchCollections
import bosca.content.transformations.DocumentReferencesToListTransformation
import bosca.content.transformations.DocumentToTextTransformation
import bosca.content.transformations.IndexableDocumentToTextTransformation
import bosca.content.transformations.MetadataDocumentToTextTransformation
import bosca.content.transformations.MetadataToSearchDocument
import bosca.content.transformations.MetadataToSearchDocumentConfiguration
import bosca.content.transformations.ProfileToSearchDocument
import bosca.content.transformations.ProfileToSearchDocumentConfiguration
import bosca.content.transformations.ReferencesListToBookListTransformation
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.nats.NatsConnectionPool
import bosca.profile.model.Profile
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.jobs.ProfileIndexExecutor
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.pubsub.PubSubService
import bosca.pubsub.RedisPubSubServiceImpl
import bosca.redis.RedisConnectionPool
import bosca.scheduler.listeners.ScheduledJobExecutionListener
import bosca.scheduler.service.SchedulerService
import bosca.search.IndexStorageSystem
import bosca.search.installer.SearchIndexInstaller
import bosca.search.installer.SearchTransformInstaller
import bosca.search.service.SearchService
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.enqueue.DefaultJobEnqueueEventChannel
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StorageSystemService
import bosca.transformations.Transformation
import bosca.server.BoscaApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Providers
class Configuration {

    @Provider(name = "search-index")
    fun searchIndexInstaller(
        storageSystemService: StorageSystemService,
        json: Json
    ): PackageInstaller = SearchIndexInstaller(
        storageSystemService,
        json
    )

    @Provider(name = "search-transform")
    fun searchTransformInstaller(
        configurationService: ConfigurationService,
        json: Json
    ): PackageInstaller = SearchTransformInstaller(
        configurationService,
        json
    )

    @Provider(name = "search-index")
    fun searchIndexPackage(): PackageInstallation = PackageInstallation(
        key = "search-index",
        name = "Search Index",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.0.0",
                installerNames = listOf("search-index")
            ),
            PackageInstallationVersion(
                version = "1.0.1",
                installerNames = listOf("search-index")
            ),
            PackageInstallationVersion(
                version = "1.0.4",
                installerNames = listOf("search-transform")
            ),
            PackageInstallationVersion(
                version = "1.0.5",
                installerNames = listOf("search-index")
            )
        )
    )

    @Provider(name = MetadataDocumentToTextProvider)
    fun metadataDocumentToText(
        metadataService: MetadataService,
        documentService: DocumentService,
        bibleService: BibleService,
        json: Json
    ): Transformation<IndexStorageSystem, Metadata, String> = MetadataDocumentToTextTransformation(metadataService, documentService, bibleService, json)

    @Provider(name = DocumentToTextProvider)
    fun indexableDocumentToText(
        metadataService: MetadataService,
        bibleService: BibleService,
        json: Json
    ): Transformation<IndexStorageSystem, LocaleAwareDocument, String> = IndexableDocumentToTextTransformation(metadataService, bibleService, json)

    @Provider
    fun documentToText(
        metadataService: MetadataService,
        bibleService: BibleService,
        json: Json
    ): DocumentToTextTransformation = DocumentToTextTransformation(metadataService, bibleService, json)

    @Provider(singleton = true)
    fun metadataToSearchDocumentConfiguration(
        collectionsService: CollectionService,
        documentService: DocumentService,
        dataService: DataService,
        guideService: GuideService,
        application: BoscaApplication
    ) = MetadataToSearchDocumentConfiguration(
        DefaultMetadataToSearchCollections(collectionsService),
        documentService,
        guideService,
        dataService,
        excludeTypes = application.environment.config.propertyOrNull("content.index.excludeTypes")?.getList()?.toSet() ?: emptySet(),
        excludeContentTypePrefix = application.environment.config.propertyOrNull("content.index.excludeContentTypePrefix")?.getList() ?: emptyList()
    )

    @Provider(singleton = true)
    fun collectionToSearchDocumentConfiguration(application: BoscaApplication) = CollectionToSearchDocumentConfiguration(
        excludeTypes = application.environment.config.propertyOrNull("content.index.excludeTypes")?.getList()?.toSet() ?: emptySet(),
        excludeContentTypePrefix = application.environment.config.propertyOrNull("content.index.excludeContentTypePrefix")?.getList() ?: emptyList()
    )

    @Provider(singleton = true)
    fun profileToSearchDocumentConfiguration(application: BoscaApplication) = ProfileToSearchDocumentConfiguration(
        excludeTypes = application.environment.config.propertyOrNull("content.index.excludeTypes")?.getList()?.toSet() ?: emptySet(),
        excludeContentTypePrefix = application.environment.config.propertyOrNull("content.index.excludeContentTypePrefix")?.getList() ?: emptyList()
    )

    @Provider(singleton = true)
    fun documentReferencesToListTransformation(
        metadataService: MetadataService,
        documentService: DocumentService,
        json: Json
    ) = DocumentReferencesToListTransformation(metadataService, documentService, json)

    @Provider(singleton = true)
    fun referencesListToBookListTransformation(
        bibleService: BibleService,
        metadataService: MetadataService
    ) = ReferencesListToBookListTransformation(bibleService, metadataService)

    @Provider(singleton = true)
    fun metadataToSearchDocument(
        metadataService: MetadataService,
        @ProviderName(MetadataDocumentToTextProvider)
        documentToText: Transformation<IndexStorageSystem, Metadata, String>,
        storage: ObjectStorageService,
        slugs: SlugService,
        configuration: MetadataToSearchDocumentConfiguration,
        documentReferences: DocumentReferencesToListTransformation,
        referencesToBookList: ReferencesListToBookListTransformation,
        configurationService: ConfigurationService,
        json: Json
    ): MetadataToSearchDocument = MetadataToSearchDocument(
        metadataService,
        documentToText,
        storage,
        slugs,
        json,
        configuration,
        documentReferences,
        referencesToBookList,
        configurationService
    )

    @Provider(name = MetadataIndexExecutor.TransformProvider)
    fun metadataToSearch(
        metadataToSearchDocument: MetadataToSearchDocument
    ): Transformation<IndexStorageSystem, Metadata, JsonElement?> = metadataToSearchDocument

    @Provider(singleton = true)
    fun collectionToSearchDocument(
        collectionService: CollectionService,
        slugs: SlugService,
        configuration: CollectionToSearchDocumentConfiguration,
        categoryService: CategoryService,
        configurationService: ConfigurationService,
        json: Json
    ): CollectionToSearchDocument = CollectionToSearchDocument(
        collectionService,
        slugs,
        json,
        configuration,
        categoryService,
        configurationService
    )

    @Provider(name = CollectionIndexExecutor.TransformProvider)
    fun collectionToSearch(
        collectionToSearchDocument: CollectionToSearchDocument
    ): Transformation<IndexStorageSystem, Collection, List<JsonElement>> = collectionToSearchDocument

    @Provider(singleton = true)
    fun profileToSearchDocument(
        profileService: ProfileService,
        organizationService: OrganizationService,
        slugs: SlugService,
        configuration: ProfileToSearchDocumentConfiguration,
        configurationService: ConfigurationService,
        json: Json
    ): ProfileToSearchDocument = ProfileToSearchDocument(
        profileService,
        organizationService,
        slugs,
        json,
        configuration,
        configurationService
    )

    @Provider(name = ProfileIndexExecutor.TransformProvider)
    fun profileToSearch(
        profileToSearchDocument: ProfileToSearchDocument
    ): Transformation<IndexStorageSystem, Profile, JsonElement?> = profileToSearchDocument

    /**
     * Provides the NATS event consumer that bridges the analytics collector
     * to the Iceberg storage layer when the runner operates as a processor.
     *
     * The event transform chain is supplied by the framework's
     * [bosca.analytics.configuration.TransformConfiguration]; its geo-enrichment
     * stage is a no-op here because NATS messages carry empty headers (Cloudflare
     * enrichment already ran in the collector).
     */
    @Provider(singleton = true)
    fun natsEventConsumer(
        nats: NatsConnectionPool,
        json: Json,
        eventRepository: EventRepository,
        eventTransforms: EventPipelineTransforms,
        config: EventProcessingConfiguration,
    ): NatsEventConsumer = NatsEventConsumer(nats, json, eventRepository, eventTransforms, config)

    @Provider
    fun bibleFactory(): BibleFactory = BibleFactoryImpl()

    @Provider(singleton = true, name = "workops")
    fun workopsJobQueue(factory: JobQueueFactory): JobQueue = factory.create("workops")

    @Provider(singleton = true, name = "workopsQueueRunner")
    fun workopsJobQueueRunner(
        @ProviderName("workops")
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(
        queue,
        100,
        distributedLockFactory,
        errorCapture,
    )

    @Provider(singleton = true, name = "gitQueueRunner")
    fun gitJobQueueRunner(
        @ProviderName("git")
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(
        queue,
        100,
        distributedLockFactory,
        errorCapture,
    )

    @Provider(singleton = true)
    fun jobEnqueueEventChannel(): JobEnqueueEventChannel = DefaultJobEnqueueEventChannel()

    @Provider
    suspend fun jobQueueFactory(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json,
        distributedLockFactory: DistributedLockFactory,
        enqueueEventChannel: JobEnqueueEventChannel
    ): JobQueueFactory {
        val enqueueCallbacks = listOf(
            JobCallback(listener = ScheduledJobExecutionListener::class)
        )
        return when (application.environment.config.propertyOrNull("jobQueue.factory")?.getString()) {
            "nats" -> NatsJobQueueFactory(natsConnectionPool.get(), json, distributedLockFactory, enqueueEventChannel, enqueueCallbacks)
            else -> RedisJobQueueFactory(redisConnectionPool.get(), json, distributedLockFactory, enqueueEventChannel, enqueueCallbacks)
        }
    }

    @Provider
    suspend fun pubsubService(application: BoscaApplication, natsConnectionPool: ObjectProvider<NatsConnectionPool>, redisConnectionPool: ObjectProvider<RedisConnectionPool>, json: Json): PubSubService {
        return when (application.environment.config.propertyOrNull("pubsub.type")?.getString()) {
            "nats" -> NatsPubSubServiceImpl(json, natsConnectionPool.get())
            else -> RedisPubSubServiceImpl(json, redisConnectionPool.get())
        }
    }

    /**
     * Selects the live-session backend used by the shared analytics transform chain. The runner can
     * receive retained or redelivered heartbeat events while acting as the analytics processor, so it
     * must provide the same binding as the collector and server composition roots.
     */
    @Provider(singleton = true)
    suspend fun liveSessions(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json,
    ): LiveSessionsService {
        val type = application.environment.config.propertyOrNull("liveSessions.type")?.getString()
            ?: application.environment.config.propertyOrNull("pubsub.type")?.getString()
        return when (type) {
            "nats" -> NatsLiveSessions(natsConnectionPool.get(), json)
            else -> RedisLiveSessions(redisConnectionPool.get(), json)
        }
    }
}
