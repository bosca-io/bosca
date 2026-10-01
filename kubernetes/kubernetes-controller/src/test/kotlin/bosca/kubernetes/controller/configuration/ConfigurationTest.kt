package bosca.kubernetes.controller.configuration

import bosca.di.asProvider
import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.repository.ClusterRepository
import bosca.kubernetes.repository.KubernetesJobExecutionRepository
import bosca.kubernetes.service.ClusterCredentialService
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.pubsub.RedisPubSubServiceImpl
import bosca.redis.RedisConnectionPool
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class ConfigurationTest {

    private val configuration = Configuration()
    private val credentials = mockk<ClusterCredentialService>(relaxed = true)
    private val lockFactory = mockk<DistributedLockFactory>(relaxed = true)
    private val nats = mockk<NatsConnectionPool>(relaxed = true)
    private val redis = mockk<RedisConnectionPool>(relaxed = true)

    @Test
    fun `constructs controller infrastructure providers`() {
        val pool = configuration.clusterClientPool(credentials)
        val informers = configuration.clusterInformerRegistry(pool)

        configuration.clusterHealthProbe(
            pool,
            informers,
            mockk<ClusterRepository>(relaxed = true),
        )
        configuration.helmIndexFetcher()
        configuration.helmRuntime(credentials)
        configuration.kubernetesJobController(
            pool,
            mockk<ClusterRepository>(relaxed = true),
            mockk<KubernetesJobExecutionRepository>(relaxed = true),
            mockk<JobQueueFactory> {
                every { create(any()) } returns mockk<JobQueue>(relaxed = true)
            },
            lockFactory,
            Json,
        )
    }

    @Test
    fun `selects NATS queue by default and Redis when configured`() = runTest {
        assertIs<NatsJobQueueFactory>(
            configuration.jobQueueFactory(
                application(),
                nats.asProvider(),
                redis.asProvider(),
                Json,
                lockFactory,
            ),
        )
        assertIs<RedisJobQueueFactory>(
            configuration.jobQueueFactory(
                application(
                    """
                    jobQueue:
                      factory: redis
                    """,
                ),
                nats.asProvider(),
                redis.asProvider(),
                Json,
                lockFactory,
            ),
        )
    }

    @Test
    fun `selects Redis pubsub by default and NATS when configured`() = runTest {
        assertIs<RedisPubSubServiceImpl>(
            configuration.pubsubService(
                application(),
                nats.asProvider(),
                redis.asProvider(),
                Json,
            ),
        )
        assertIs<NatsPubSubServiceImpl>(
            configuration.pubsubService(
                application(
                    """
                    pubsub:
                      type: nats
                    """,
                ),
                nats.asProvider(),
                redis.asProvider(),
                Json,
            ),
        )
    }

    private fun application(yaml: String = "{}"): BoscaApplication {
        val application = mockk<BoscaApplication>()
        every { application.environment.config } returns ApplicationConfig.load(
            yaml.trimIndent().byteInputStream(),
        )
        return application
    }
}
