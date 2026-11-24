package bosca.server.configuration

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.livesessions.nats.NatsLiveSessions
import bosca.analytics.livesessions.redis.RedisLiveSessions
import bosca.analytics.repository.EventRepository
import bosca.analytics.service.AnalyticsEventConsumer
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.pubsub.PubSubService
import bosca.redis.RedisConnectionPool
import bosca.scheduler.listeners.ScheduledJobExecutionListener
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.enqueue.DefaultJobEnqueueEventChannel
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import kotlinx.serialization.json.Json

/**
 * DI configuration for the analytics processor, providing the job queue
 * infrastructure, pub/sub service, and the NATS event consumer that bridges
 * the collector to Iceberg storage.
 *
 * The event transform chain is provided by the analytics module's
 * `TransformConfiguration` (shared with the collector); the consumer resolves
 * it via [EventPipelineTransforms].
 */
@Providers
class Configuration {

    @Provider(name = "sharedqueue-migrations")
    fun sharedQueueMigration(): bosca.db.migrations.Migration = bosca.sharedqueue.jobs.postgres.PostgresJobQueueMigration()

    @Provider(singleton = true)
    fun analyticsEventConsumer(
        queue: bosca.analytics.queue.AnalyticsEventQueue,
        @bosca.di.annotation.ProviderName("analytics-event-storage") eventRepository: EventRepository,
        eventTransforms: EventPipelineTransforms,
        config: EventProcessingConfiguration,
    ): AnalyticsEventConsumer = AnalyticsEventConsumer(queue, eventRepository, eventTransforms, config)

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
            "postgres" -> bosca.sharedqueue.jobs.postgres.PostgresJobQueueFactory(bosca.di.provide(bosca.db.PostgresInfrastructurePoolProvider.NAME), json, enqueueEventChannel, enqueueCallbacks)
            "nats" -> NatsJobQueueFactory(natsConnectionPool.get(), json, distributedLockFactory, enqueueEventChannel, enqueueCallbacks, false)
            else -> RedisJobQueueFactory(redisConnectionPool.get(), json, distributedLockFactory, enqueueEventChannel, enqueueCallbacks, false)
        }
    }

    @Provider(singleton = true)
    suspend fun pubsubService(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json
    ): PubSubService {
        return when (application.environment.config.propertyOrNull("pubsub.type")?.getString()) {
            "postgres" -> bosca.pubsub.PostgresPubSubService.create(application)
            "nats" -> NatsPubSubServiceImpl(json, natsConnectionPool.get())
            else -> bosca.pubsub.RedisPubSubServiceImpl(json, redisConnectionPool.get())
        }
    }

    /**
     * Selects the live-session backend used by the shared analytics transform chain. Retained or
     * redelivered heartbeat events can reach the processor, so the binding must be available here as
     * well as in the collector.
     */
    @Provider(singleton = true)
    suspend fun liveSessions(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json,
    ): LiveSessionsService {
        val type = application.environment.config.propertyOrNull("liveSessions.type")?.getString()?.takeIf(String::isNotBlank)
            ?: application.environment.config.propertyOrNull("pubsub.type")?.getString()
        return when (type) {
            // Live-session maps have no PostgreSQL backend, so PostgreSQL pub/sub disables them.
            "disabled", "postgres" -> bosca.analytics.livesessions.DisabledLiveSessions()
            "nats" -> NatsLiveSessions(natsConnectionPool.get(), json)
            else -> RedisLiveSessions(redisConnectionPool.get(), json)
        }
    }
}
