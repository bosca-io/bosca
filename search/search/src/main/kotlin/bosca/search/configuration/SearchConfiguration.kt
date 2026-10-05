@file:Suppress("OPT_IN_USAGE")

package bosca.search.configuration

import bosca.configuration.service.ConfigurationService
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.di.ObjectProvider
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.meilisearch.client.MeilisearchClient
import bosca.search.index.IndexInitializer
import bosca.search.index.IndexInitializerJobFactoryImpl
import bosca.search.service.IndexInitializerJobFactory
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.storage.service.StorageSystemService
import bosca.server.BoscaApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class MeilisearchConfiguration(
    val url: String,
    val apiKey: String,
    val experimental: ExperimentalConfiguration? = null,
    /** Prepended to every index and chat workspace UID so installations can share one Meilisearch service. */
    val indexPrefix: String = "",
) {
    /** Meilisearch accepts only these characters in index and chat workspace UIDs. */
    val hasValidIndexPrefix: Boolean get() = VALID_INDEX_PREFIX.matches(indexPrefix)

    /** Resolves a database-owned index name to its UID on a shared Meilisearch service. */
    fun indexUid(name: String): String = "$indexPrefix$name"

    /** Resolves a database-owned chat workspace name to its UID on a shared Meilisearch service. */
    fun chatWorkspaceUid(name: String): String = "$indexPrefix$name"
}

private val VALID_INDEX_PREFIX = Regex("[A-Za-z0-9_-]*")

@Serializable
data class ExperimentalConfiguration(
    val chatCompletions: Boolean = false,
)

object JobQueueNames {
    const val indexJobQueue = "indexQueue"
    const val indexRunner = "indexQueueRunner"
    const val indexQueue = "index"
}

@Providers
class SearchConfiguration {
    private val log = LoggerFactory.getLogger(SearchConfiguration::class.java)

    @Provider(singleton = true)
    fun searchClientConfiguration(application: BoscaApplication): MeilisearchConfiguration {
        val configuration = application.environment.config.property("meilisearch").getAs<MeilisearchConfiguration>()
        if (!configuration.hasValidIndexPrefix) {
            // Keep the prefix: dropping it would read and write another installation's unprefixed indexes.
            // Meilisearch rejects the resulting UIDs, so search requests fail with its error instead.
            log.error(
                "meilisearch.indexPrefix '{}' may contain only letters, digits, '-' and '_'; search requests will fail",
                configuration.indexPrefix,
            )
        }
        return configuration
    }

    @Provider(singleton = true)
    fun indexInitializerFactory(
        @ProviderName(JobQueueNames.indexJobQueue)
        jobQueue: JobQueue
    ): IndexInitializerJobFactory = IndexInitializerJobFactoryImpl(jobQueue)

    @Provider(singleton = true)
    fun indexInitializer(
        configuration: ConfigurationService,
        meilisearchConfiguration: MeilisearchConfiguration,
        storageSystemService: StorageSystemService,
        json: Json,
        meilisearchClient: MeilisearchClient,
        @ProviderName(bosca.profile.configuration.JobQueueNames.profileJobQueue)
        profileJobQueue: JobQueue,
    ): IndexInitializer = IndexInitializer(
        configuration,
        storageSystemService,
        meilisearchConfiguration,
        json,
        meilisearchClient,
        profileJobQueue,
    )

    @Provider(singleton = true)
    fun searchClient(
        configuration: MeilisearchConfiguration,
        json: Json,
        application: BoscaApplication,
    ): MeilisearchClient {
        val client = MeilisearchClient(
            url = configuration.url,
            apiKey = configuration.apiKey,
            json = json,
        )
        application.onShutdown { client.close() }
        return client
    }

    @Provider(singleton = true, name = JobQueueNames.indexJobQueue)
    fun indexJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.indexQueue)

    @Provider(name = JobQueueNames.indexRunner)
    fun indexJobQueueRunner(
        @ProviderName(JobQueueNames.indexJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(
        queue,
        100,
        distributedLockFactory,
        errorCapture,
    )
}
