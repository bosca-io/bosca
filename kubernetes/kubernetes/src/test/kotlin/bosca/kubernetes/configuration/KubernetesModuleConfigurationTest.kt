package bosca.kubernetes.configuration

import bosca.kubernetes.jobs.KubernetesJobQueueNames
import bosca.kubernetes.migration.KubernetesMigration
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class KubernetesModuleConfigurationTest {

    private val configuration = KubernetesModuleConfiguration()

    @Test
    fun `provides migrations queue and controller client`() {
        assertIs<KubernetesMigration>(configuration.migration())

        val queue = mockk<JobQueue>()
        val factory = mockk<JobQueueFactory> {
            every { create(KubernetesJobQueueNames.queue) } returns queue
        }
        assertSame(queue, configuration.kubernetesJobsQueue(factory))
        verify { factory.create(KubernetesJobQueueNames.queue) }

        assertIs<KubernetesControllerClient>(
            configuration.kubernetesControllerClient(mockk<SecurityService>()),
        )
    }
}
