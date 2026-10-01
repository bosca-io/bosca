@file:OptIn(InternalDI::class)

package bosca.workops.jobs

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.FailException
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.HealthCheckStatus
import bosca.workops.repository.EnvironmentDeploymentRepository
import bosca.workops.service.EnvironmentService
import bosca.workops.service.EnvironmentServiceImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [WaitForHealthyExecutor]: durable health wait — status first, active URL probe,
 * delay-until-healthy. The probe semantics execute through the REAL
 * [EnvironmentServiceImpl.probeHealth] (shared with the CI verify-deployment gate), so this is
 * the single implementation both paths run.
 */
class WaitForHealthyExecutorTest {

    private val deployRepository = mockk<EnvironmentDeploymentRepository>()
    private val projectRepository = mockk<bosca.workops.repository.ProjectRepository>()
    private val envRepository = mockk<bosca.workops.repository.EnvironmentRepository>()
    private val service = EnvironmentServiceImpl(
        envRepository, deployRepository, mockk(relaxed = true), projectRepository,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true),
    )
    private val executor = WaitForHealthyExecutor()

    private val deploymentId = UUID.random()

    private fun deployment(status: HealthCheckStatus, url: String? = null) = EnvironmentDeployment(
        id = deploymentId, environmentId = UUID.random(), projectId = UUID.random(), versionId = UUID.random(),
        healthCheckStatus = status, healthCheckUrl = url, version = 2,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<EnvironmentService> { service }
        coEvery { projectRepository.getById(any()) } returns null
        coEvery { envRepository.getById(any()) } returns null
        coEvery { deployRepository.currentState(any()) } returns emptyList()
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `a deployment already HEALTHY completes immediately`() = runTest {
        coEvery { deployRepository.getById(deploymentId) } returns deployment(HealthCheckStatus.HEALTHY)
        executor.awaitHealthy(deploymentId) { error("must not probe") }
    }

    @Test
    fun `a 2xx probe records HEALTHY and completes`() = runTest {
        coEvery { deployRepository.getById(deploymentId) } returns
            deployment(HealthCheckStatus.UNKNOWN, url = "http://svc/health")
        coEvery { deployRepository.updateHealthCheck(deploymentId, HealthCheckStatus.HEALTHY.name, 2) } returns
            deployment(HealthCheckStatus.HEALTHY)

        executor.awaitHealthy(deploymentId) { url -> url == "http://svc/health" }

        coVerify(exactly = 1) { deployRepository.updateHealthCheck(deploymentId, HealthCheckStatus.HEALTHY.name, 2) }
    }

    @Test
    fun `a failing probe reschedules rather than failing — rollouts are allowed to be briefly unhealthy`() = runTest {
        coEvery { deployRepository.getById(deploymentId) } returns
            deployment(HealthCheckStatus.UNKNOWN, url = "http://svc/health")
        assertFailsWith<DelayException> { executor.awaitHealthy(deploymentId) { false } }
        coVerify(exactly = 0) { deployRepository.updateHealthCheck(any(), any(), any()) }
    }

    @Test
    fun `a probe that throws is a retry, not a failure`() = runTest {
        coEvery { deployRepository.getById(deploymentId) } returns
            deployment(HealthCheckStatus.UNKNOWN, url = "http://svc/health")
        assertFailsWith<DelayException> { executor.awaitHealthy(deploymentId) { error("connection refused") } }
    }

    @Test
    fun `no health-check URL waits passively for an observer to record health`() = runTest {
        coEvery { deployRepository.getById(deploymentId) } returns deployment(HealthCheckStatus.UNKNOWN)
        assertFailsWith<DelayException> { executor.awaitHealthy(deploymentId) { error("must not probe") } }
    }

    @Test
    fun `a missing deployment fails the wait`() = runTest {
        coEvery { deployRepository.getById(deploymentId) } returns null
        assertFailsWith<FailException> { executor.awaitHealthy(deploymentId) { false } }
    }
}
