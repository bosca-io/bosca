package bosca.atlas.server

import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.git.installer.GitSearchIndexInstaller
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.pubsub.PubSubService
import bosca.pubsub.RedisPubSubServiceImpl
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import bosca.sharedqueue.jobs.enqueue.DefaultJobEnqueueEventChannel
import bosca.scheduler.listeners.ScheduledJobExecutionListener
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import bosca.redis.RedisConnectionPool
import bosca.storage.service.StorageSystemService
import kotlinx.serialization.json.Json

@Providers
class Configuration {
    @Provider(name = "default-artifact-sync-pipelines")
    fun artifactSyncInstaller(pipelines: bosca.pipelines.service.PipelineService): PackageInstaller =
        bosca.artifacts.installer.DefaultArtifactSyncPipelinesInstaller(pipelines)

    @Provider(name = "default-artifact-sync-pipelines-package")
    fun artifactSyncPackage(): PackageInstallation = PackageInstallation(
        key = "default-artifact-sync-pipelines", name = "Default Artifact Sync Pipelines",
        versions = listOf(PackageInstallationVersion(version = "1.1.0", installerNames = listOf("default-artifact-sync-pipelines"))),
    )

    @Provider(singleton = true)
    fun atlasConfiguration(application: BoscaApplication): AtlasConfiguration = AtlasConfiguration.from(application.environment.config)

    @Provider(singleton = true)
    fun jobEnqueueEventChannel(): JobEnqueueEventChannel = DefaultJobEnqueueEventChannel()


    /** Selects the site's pub/sub backend for Git events shared with the server and runner. */
    @Provider
    suspend fun pubsubService(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json,
    ): PubSubService {
        return when (application.environment.config.propertyOrNull("pubsub.type")?.getString()) {
            "nats" -> NatsPubSubServiceImpl(json, natsConnectionPool.get())
            else -> RedisPubSubServiceImpl(json, redisConnectionPool.get())
        }
    }

    @Provider(singleton = true)
    suspend fun jobQueueFactory(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json,
        distributedLockFactory: DistributedLockFactory,
        enqueueEventChannel: JobEnqueueEventChannel,
        configuration: AtlasConfiguration,
    ): JobQueueFactory {
        val callbacks = listOf(JobCallback(listener = ScheduledJobExecutionListener::class))
        val factories: Pair<JobQueueFactory, JobQueueFactory> = when (application.environment.config.propertyOrNull("jobQueue.factory")?.getString()) {
            "nats" -> {
                val connection = natsConnectionPool.get()
                NatsJobQueueFactory(connection, json, distributedLockFactory, enqueueEventChannel, callbacks, false) to
                    NatsJobQueueFactory(connection, json, distributedLockFactory, enqueueEventChannel, callbacks, true)
            }
            else -> {
                val connection = redisConnectionPool.get()
                RedisJobQueueFactory(connection, json, distributedLockFactory, enqueueEventChannel, callbacks, false) to
                    RedisJobQueueFactory(connection, json, distributedLockFactory, enqueueEventChannel, callbacks, true)
            }
        }
        val producer: JobQueueFactory = factories.first
        val consumer: JobQueueFactory = factories.second
        return object : JobQueueFactory {
            override fun create(name: String): JobQueue =
                if (configuration.runnerEnabled && name in configuration.runnerQueues) consumer.create(name)
                else producer.create(name)
        }
    }

    @Provider(name = "git-search-index")
    fun gitSearchIndexInstaller(
        storageSystemService: StorageSystemService,
        json: Json,
    ): PackageInstaller {
        return GitSearchIndexInstaller(storageSystemService, json)
    }

    @Provider(name = "git-search-index")
    fun gitSearchIndexPackage(): PackageInstallation {
        return PackageInstallation(
            key = "git-search-index",
            name = "Git Search Index",
            versions = listOf(
                PackageInstallationVersion(
                    version = "1.0.0",
                    installerNames = listOf("git-search-index")
                )
            )
        )
    }
}
