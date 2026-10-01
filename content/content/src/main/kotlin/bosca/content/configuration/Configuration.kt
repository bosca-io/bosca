package bosca.content.configuration

import bosca.content.collection.installer.BiblesCollectionInstaller
import bosca.content.collection.installer.RootCollectionInstaller
import bosca.content.collection.repository.CollectionFindRepository
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.embedding.model.EmbeddingConfiguration
import bosca.content.healthcheck.ContentHealthCheckRepository
import bosca.content.metadata.repository.MetadataFindRepository
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.state.service.StateService
import bosca.content.tools.installer.ToolsPackageInstaller
import bosca.content.tools.service.TemplateAttributeToolService
import bosca.content.transition.jobs.UpdateContentJobHistoryOnCompleteListener
import bosca.content.transition.service.TransitionService
import bosca.content.transition.service.Transitioner
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.slug.service.SlugService
import kotlinx.serialization.json.Json

/**
 * Maximum number of items that can be processed in a single bulk GraphQL mutation.
 * Prevents long-running transactions, excessive DB queries, and potential OOM from
 * unbounded client input.
 */
const val MAX_BULK_OPERATION_SIZE = 500

object JobQueueNames {
    const val contentJobQueue = "contentQueue"
    const val contentRunner = "contentQueueRunner"
    const val contentQueue = "content"
}

@Providers
class Configuration {

    @Provider(singleton = true)
    fun collectionFind(json: Json) = CollectionFindRepository(json)

    @Provider(singleton = true)
    fun metadataFind(json: Json) = MetadataFindRepository(json)

    @Provider(singleton = true)
    fun contentHealthCheck() = ContentHealthCheckRepository()

    @Provider(singleton = true)
    fun embeddingConfiguration(application: BoscaApplication) = with(application.environment.config) {
        val defaults = EmbeddingConfiguration()
        EmbeddingConfiguration(
            enabled = propertyOrNull("embedding.enabled")?.getString()?.toBoolean() ?: defaults.enabled,
            url = propertyOrNull("embedding.url")?.getString() ?: defaults.url,
            model = propertyOrNull("embedding.model")?.getString() ?: defaults.model,
            dimension = propertyOrNull("embedding.dimension")?.getString()?.toIntOrNull() ?: defaults.dimension,
            documentPrompt = propertyOrNull("embedding.documentPrompt")?.getString() ?: defaults.documentPrompt,
            chunkOverlapTokens = propertyOrNull("embedding.chunkOverlapTokens")?.getString()?.toIntOrNull()
                ?: defaults.chunkOverlapTokens,
            timeoutSeconds = propertyOrNull("embedding.timeoutSeconds")?.getString()?.toIntOrNull() ?: defaults.timeoutSeconds,
        )
    }

    @Provider(singleton = true, name = JobQueueNames.contentJobQueue)
    fun contentJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.contentQueue)

    @Provider(singleton = true, name = JobQueueNames.contentRunner)
    fun contentJobQueueRunner(
        @ProviderName(JobQueueNames.contentJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(
        queue,
        100,
        distributedLockFactory,
        errorCapture,
    )

    @Provider
    fun updateContentJobHistoryOnCompleteListener(
        metadataJobHistoryService: MetadataJobHistoryService,
        collectionJobHistoryService: CollectionJobHistoryService,
        metadataService: MetadataService,
        collectionService: CollectionService,
        securityService: SecurityService,
        transitioner: Transitioner,
    ): UpdateContentJobHistoryOnCompleteListener = UpdateContentJobHistoryOnCompleteListener(
        metadataJobHistoryService,
        collectionJobHistoryService,
        metadataService,
        collectionService,
        securityService,
        transitioner,
    )

    @Provider(singleton = true)
    fun transitioner(
        metadataService: MetadataService,
        metadataJobHistoryService: MetadataJobHistoryService,
        collectionService: CollectionService,
        collectionJobHistoryRepository: CollectionJobHistoryService,
        transitionService: TransitionService,
        stateService: StateService,
        metadataPermissionEvaluator: MetadataPermissionEvaluator,
        collectionPermissionEvaluator: CollectionPermissionEvaluator
    ) = Transitioner(
        metadataService,
        metadataJobHistoryService,
        collectionService,
        collectionJobHistoryRepository,
        transitionService,
        stateService,
        metadataPermissionEvaluator,
        collectionPermissionEvaluator,
    )

    @Provider(singleton = true, name = "root-collection-installer")
    fun rootCollectionInstaller(collectionService: CollectionService): PackageInstaller = RootCollectionInstaller(collectionService)

    @Provider(singleton = true, name = "bibles-collection-installer")
    fun biblesCollectionInstaller(collectionService: CollectionService, slugService: SlugService): PackageInstaller = BiblesCollectionInstaller(collectionService, slugService)

    @Provider(singleton = true, name = "tools-installer")
    fun toolsInstaller(service: TemplateAttributeToolService): PackageInstaller = ToolsPackageInstaller(service)

    @Provider(name = "root-collection-installer")
    fun rootCollectionPackage(): PackageInstallation = PackageInstallation(
        key = "root-collection-installer",
        name = "Root Collection Installer",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.0.0",
                installerNames = listOf("root-collection-installer")
            )
        )
    )

    @Provider(name = "bible")
    fun biblePackage(): PackageInstallation = PackageInstallation(
        key = "bible",
        name = "Bible",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.0.0",
                installerNames = listOf("bibles-collection-installer")
            )
        )
    )
}
