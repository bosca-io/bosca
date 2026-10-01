@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.pipelines.service.PipelineRunService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * The operator-facing run controls are admin-gated. These prove the gate is actually enforced (a
 * non-admin can't cancel a run) and that an authorized call delegates to the run service.
 */
class PipelineRunControlsTest {

    @Test
    fun `cancelRun requires admin and cancels the run`() = runTest {
        val groups = mockk<GroupEvaluator>(relaxed = true)
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = mutationController(groups, runService)
        val runId = UUID.random()

        assertTrue(controller.cancelRun(mockk<AuthenticationContext>(), runId))
        coVerify(exactly = 1) { runService.cancel(runId, "cancelled by operator") }
    }

    @Test
    fun `cancelRun rejects a non-admin and does not cancel`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val runService = mockk<PipelineRunService>(relaxed = true)
        val controller = mutationController(groups, runService)

        assertFailsWith<IllegalStateException> { controller.cancelRun(mockk<AuthenticationContext>(), UUID.random()) }
        coVerify(exactly = 0) { runService.cancel(any(), any()) }
    }

    @Test
    fun `activeRuns requires admin and lists in-flight runs`() = runTest {
        val groups = mockk<GroupEvaluator>(relaxed = true)
        val runService = mockk<PipelineRunService>()
        coEvery { runService.listActive(0, 50) } returns emptyList()
        val controller = PipelinesController(mockk(relaxed = true), groups, runService, mockk(relaxed = true), mockk(relaxed = true))

        controller.activeRuns(mockk<AuthenticationContext>(), null, null)
        coVerify(exactly = 1) { runService.listActive(0, 50) }
    }

    @Test
    fun `run requires admin and fetches the run by id`() = runTest {
        val groups = mockk<GroupEvaluator>(relaxed = true)
        val runService = mockk<PipelineRunService>()
        val runId = UUID.random()
        coEvery { runService.get(runId) } returns null
        val controller = PipelinesController(mockk(relaxed = true), groups, runService, mockk(relaxed = true), mockk(relaxed = true))

        controller.run(mockk<AuthenticationContext>(), runId)
        coVerify(exactly = 1) { runService.get(runId) }
    }

    @Test
    fun `nodeMetrics requires admin and aggregates per node`() = runTest {
        val groups = mockk<GroupEvaluator>(relaxed = true)
        val runService = mockk<PipelineRunService>()
        val pipelineId = UUID.random()
        coEvery { runService.nodeMetrics(pipelineId) } returns emptyList()
        val controller = PipelinesController(mockk(relaxed = true), groups, runService, mockk(relaxed = true), mockk(relaxed = true))

        controller.nodeMetrics(mockk<AuthenticationContext>(), pipelineId)
        coVerify(exactly = 1) { runService.nodeMetrics(pipelineId) }
    }

    @Test
    fun `nodeMetrics rejects a non-admin`() = runTest {
        val groups = mockk<GroupEvaluator>()
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
        val runService = mockk<PipelineRunService>()
        val controller = PipelinesController(mockk(relaxed = true), groups, runService, mockk(relaxed = true), mockk(relaxed = true))

        assertFailsWith<IllegalStateException> { controller.nodeMetrics(mockk<AuthenticationContext>(), UUID.random()) }
        coVerify(exactly = 0) { runService.nodeMetrics(any()) }
    }

    private fun mutationController(groups: GroupEvaluator, runService: PipelineRunService) =
        PipelinesMutationController(
            mockk(relaxed = true), groups, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), runService, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true),
        )
}
