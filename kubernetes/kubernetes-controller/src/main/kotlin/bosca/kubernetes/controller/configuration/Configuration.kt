package bosca.kubernetes.controller.configuration

import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.cluster.ClusterHealthProbe
import bosca.kubernetes.controller.cluster.ClusterInformerRegistry
import bosca.kubernetes.controller.helm.HelmIndexFetcher
import bosca.kubernetes.controller.helm.HelmRuntime
import bosca.kubernetes.controller.jobs.KubernetesJobController
import bosca.kubernetes.repository.ClusterRepository
import bosca.kubernetes.repository.KubernetesJobExecutionRepository
import bosca.kubernetes.service.ClusterCredentialService
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.pubsub.PubSubService
import bosca.pubsub.RedisPubSubServiceImpl
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import kotlinx.serialization.json.Json

/**
 * Wires `kubernetes-controller`'s runtime singletons into Bosca's DI
 * container. The controller binary owns the fabric8 client pool plus
 * the informer registry — every other kubernetes-aware service in the
 * process eventually traces back to one of these two singletons.
 *
 * Also publishes the [PubSubService] the security stack's
 * [bosca.security.service.ApiTokenServiceImpl] needs to invalidate
 * cached tokens across processes. Bosca-server publishes the same
 * provider from its own `Configuration`; the controller mirrors it
 * so [bosca.security.routes.AuthenticationModule] resolves cleanly
 * when this binary boots standalone.
 */
@Providers
class Configuration {

    @Provider(singleton = true)
    fun clusterClientPool(credentials: ClusterCredentialService) = ClusterClientPool(credentials)

    @Provider(singleton = true)
    fun clusterInformerRegistry(pool: ClusterClientPool) = ClusterInformerRegistry(pool)

    @Provider(singleton = true)
    fun clusterHealthProbe(
        pool: ClusterClientPool,
        informers: ClusterInformerRegistry,
        clusters: ClusterRepository,
    ) = ClusterHealthProbe(pool, informers, clusters)

    @Provider(singleton = true)
    fun helmIndexFetcher() = HelmIndexFetcher()

    @Provider(singleton = true)
    fun helmRuntime(credentials: ClusterCredentialService) = HelmRuntime(credentials)

    @Provider
    suspend fun jobQueueFactory(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json,
        distributedLockFactory: DistributedLockFactory,
    ): JobQueueFactory = when (
        application.environment.config.propertyOrNull("jobQueue.factory")?.getString()
    ) {
        "redis" -> RedisJobQueueFactory(
            redisConnectionPool.get(),
            json,
            distributedLockFactory,
            enqueueEventChannel = null,
            enqueueCallbacks = emptyList(),
            processExpiredJobs = true,
        )
        else -> NatsJobQueueFactory(
            natsConnectionPool.get(),
            json,
            distributedLockFactory,
            enqueueEventChannel = null,
            enqueueCallbacks = emptyList(),
            processExpiredJobs = true,
        )
    }

    @Provider(singleton = true)
    fun kubernetesJobController(
        pool: ClusterClientPool,
        clusters: ClusterRepository,
        executions: KubernetesJobExecutionRepository,
        queueFactory: JobQueueFactory,
        distributedLockFactory: DistributedLockFactory,
        json: Json,
    ) = KubernetesJobController(
        pool,
        clusters,
        executions,
        queueFactory,
        distributedLockFactory,
        json,
    )

    @Provider
    suspend fun pubsubService(
        application: BoscaApplication,
        natsConnectionPool: ObjectProvider<NatsConnectionPool>,
        redisConnectionPool: ObjectProvider<RedisConnectionPool>,
        json: Json,
    ): PubSubService = when (application.environment.config.propertyOrNull("pubsub.type")?.getString()) {
        "nats" -> NatsPubSubServiceImpl(json, natsConnectionPool.get())
        else -> RedisPubSubServiceImpl(json, redisConnectionPool.get())
    }
}
