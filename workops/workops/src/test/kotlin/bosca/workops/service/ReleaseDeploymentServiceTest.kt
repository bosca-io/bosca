package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.repository.ReleaseProjectVersionDeploymentRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReleaseDeploymentServiceTest {
    private val repository = mockk<ReleaseProjectVersionDeploymentRepository>()
    private val service = ReleaseDeploymentServiceImpl(repository)
    private val releaseId = UUID.random()
    private val projectId = UUID.random()
    private val versionId = UUID.random()

    @Test
    fun `every missing deployment mutation identifies the bundled project version`() = runTest {
        coEvery { repository.setDeploymentOrder(releaseId, projectId, versionId, 2) } returns null
        coEvery { repository.updateDeploymentStatus(releaseId, projectId, versionId, any(), any(), any()) } returns null
        coEvery { repository.setRollbackVersion(releaseId, projectId, versionId, any()) } returns null

        val failures = listOf(
            assertFailsWith<WorkOpsNotFoundException> {
                service.setDeploymentOrder(releaseId, projectId, versionId, 2)
            },
            assertFailsWith<WorkOpsNotFoundException> {
                service.markDeploying(releaseId, projectId, versionId, UUID.random())
            },
            assertFailsWith<WorkOpsNotFoundException> {
                service.markDeployed(releaseId, projectId, versionId, UUID.random())
            },
            assertFailsWith<WorkOpsNotFoundException> {
                service.markRolledBack(releaseId, projectId, versionId, UUID.random())
            },
        )

        failures.forEach { failure ->
            assertEquals("ReleaseProjectVersion", failure.type)
            assertEquals("$releaseId/$projectId/$versionId", failure.handle)
        }
    }
}
