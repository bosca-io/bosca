package bosca.git.server

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
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import bosca.redis.RedisConnectionPool
import bosca.storage.service.StorageSystemService
import kotlinx.serialization.json.Json

@Providers
class Configuration {

    @Provider
    suspend fun pubsubService(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        json: Json,
    ): PubSubService {
        return NatsPubSubServiceImpl(json, natsConnectionPool.get())
    }

    @Provider(singleton = true)
    suspend fun jobQueueFactory(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json,
        distributedLockFactory: DistributedLockFactory,
    ): JobQueueFactory {
        return when (application.environment.config.propertyOrNull("jobQueue.factory")?.getString()) {
            "nats" -> NatsJobQueueFactory(natsConnectionPool.get(), json, distributedLockFactory, null, emptyList(), false)
            else -> RedisJobQueueFactory(redisConnectionPool.get(), json, distributedLockFactory, null, emptyList(), false)
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
