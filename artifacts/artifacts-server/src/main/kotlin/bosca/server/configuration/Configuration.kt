package bosca.server.configuration

import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.nats.NatsConnectionPool
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.pubsub.PubSubService
import bosca.pubsub.RedisPubSubServiceImpl
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import bosca.lock.DistributedLockFactory
import bosca.pipelines.PipelineEventDispatcher
import bosca.pipelines.configuration.PipelinesConfiguration
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.trigger.PipelineEventDispatcherImpl
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import kotlinx.serialization.json.Json

/**
 * Server-level DI providers for the artifacts server.
 *
 * Provides infrastructure services (PubSub, etc.) that framework-level registrars
 * depend on but that are not provided by any framework module — each server must
 * supply these based on its own configuration.
 */
@Providers
class Configuration {

    // Registry writes produce ordinary pipeline events; the Bosca runner matches and executes them.
    @Provider(singleton = true)
    fun pipelineEventDispatcher(json: Json): PipelineEventDispatcher = PipelineEventDispatcherImpl(null, json)

    @Provider(singleton = true, name = PipelinesJobQueueNames.jobQueue)
    fun pipelinesJobQueue(factory: JobQueueFactory): JobQueue = PipelinesConfiguration().pipelinesJobQueue(factory)

    @Provider(singleton = true)
    suspend fun jobQueueFactory(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json,
        distributedLockFactory: DistributedLockFactory,
    ): JobQueueFactory = when (application.environment.config.propertyOrNull("jobQueue.factory")?.getString()) {
        "nats" -> NatsJobQueueFactory(natsConnectionPool.get(), json, distributedLockFactory, null, emptyList(), processExpiredJobs = false)
        else -> RedisJobQueueFactory(redisConnectionPool.get(), json, distributedLockFactory, null, emptyList(), processExpiredJobs = false)
    }

    /**
     * Provides the [PubSubService] implementation based on the `pubsub.type` configuration.
     * Required by [bosca.security.service.ApiTokenServiceImpl] for cross-instance
     * token cache invalidation on revocation.
     */
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
}
