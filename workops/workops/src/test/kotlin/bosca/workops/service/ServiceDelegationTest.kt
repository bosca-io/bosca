package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.ai.AiOptInScope
import bosca.workops.model.ai.AiOptOutException
import bosca.workops.model.capacity.Capacity
import bosca.workops.repository.AiOptInRepository
import bosca.workops.repository.CapacityRepository
import bosca.workops.repository.OutboundMessageIdRepository
import bosca.workops.repository.SprintAssigneeAggregate
import bosca.workops.repository.SprintWorkRepository
import bosca.workops.repository.TaskWatcherRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceDelegationTest {

    @Test
    fun `ai enabled state evaluates every hierarchy level`() = runTest {
        val orgDisabledRepository = mockk<AiOptInRepository>()
        coEvery { orgDisabledRepository.orgEnabled() } returns false
        val disabledProjectId = UUID.random()
        val enabledProjectId = UUID.random()
        val disabledTaskId = UUID.random()
        val enabledTaskId = UUID.random()
        val scopedRepository = mockk<AiOptInRepository>()
        coEvery { scopedRepository.orgEnabled() } returns true
        coEvery { scopedRepository.projectEnabled(disabledProjectId) } returns false
        coEvery { scopedRepository.projectEnabled(enabledProjectId) } returns true
        coEvery { scopedRepository.taskOverride(disabledTaskId) } returns false
        coEvery { scopedRepository.taskOverride(enabledTaskId) } returns null
        val orgDisabled = AiOptInServiceImpl(orgDisabledRepository)
        val scoped = AiOptInServiceImpl(scopedRepository)

        assertFalse(orgDisabled.isEnabled(null, null))
        assertTrue(scoped.isEnabled(null, null))
        assertFalse(scoped.isEnabled(disabledProjectId, null))
        assertFalse(scoped.isEnabled(enabledProjectId, disabledTaskId))
        assertTrue(scoped.isEnabled(enabledProjectId, enabledTaskId))

        coVerify(exactly = 0) { orgDisabledRepository.projectEnabled(any()) }
        coVerify(exactly = 0) { orgDisabledRepository.taskOverride(any()) }
    }

    @Test
    fun `ai requirement reports the scope that opted out and accepts enabled scopes`() = runTest {
        val orgDisabledRepository = mockk<AiOptInRepository>()
        coEvery { orgDisabledRepository.orgEnabled() } returns false
        val disabledProjectId = UUID.random()
        val enabledProjectId = UUID.random()
        val disabledTaskId = UUID.random()
        val enabledTaskId = UUID.random()
        val scopedRepository = mockk<AiOptInRepository>()
        coEvery { scopedRepository.orgEnabled() } returns true
        coEvery { scopedRepository.projectEnabled(disabledProjectId) } returns false
        coEvery { scopedRepository.projectEnabled(enabledProjectId) } returns true
        coEvery { scopedRepository.taskOverride(disabledTaskId) } returns false
        coEvery { scopedRepository.taskOverride(enabledTaskId) } returns true
        val orgDisabled = AiOptInServiceImpl(orgDisabledRepository)
        val scoped = AiOptInServiceImpl(scopedRepository)

        val orgFailure = assertFailsWith<AiOptOutException> {
            orgDisabled.requireEnabled(null, null)
        }
        val projectFailure = assertFailsWith<AiOptOutException> {
            scoped.requireEnabled(disabledProjectId, null)
        }
        val taskFailure = assertFailsWith<AiOptOutException> {
            scoped.requireEnabled(enabledProjectId, disabledTaskId)
        }
        scoped.requireEnabled(null, null)
        scoped.requireEnabled(enabledProjectId, enabledTaskId)

        assertEquals(AiOptInScope.ORG, orgFailure.scope)
        assertNull(orgFailure.scopeId)
        assertEquals(AiOptInScope.PROJECT, projectFailure.scope)
        assertEquals(disabledProjectId, projectFailure.scopeId)
        assertEquals(AiOptInScope.TASK, taskFailure.scope)
        assertEquals(disabledTaskId, taskFailure.scopeId)
    }

    @Test
    fun `remaining outbound watcher and capacity delegates preserve their keys`() = runTest {
        val messageRepository = mockk<OutboundMessageIdRepository>()
        val watcherRepository = mockk<TaskWatcherRepository>()
        val capacityRepository = mockk<CapacityRepository>()
        val workRepository = mockk<SprintWorkRepository>()
        val messageId = "<message-42@example.com>"
        val profileId = UUID.random()
        val sprintId = UUID.random()
        val capacity = Capacity(sprintId, profileId, committedSeconds = 14_400)
        coEvery { messageRepository.resolve(messageId) } returns null
        coEvery { watcherRepository.listByProfile(profileId) } returns emptyList()
        coEvery { capacityRepository.listForSprint(sprintId) } returns listOf(capacity)
        coEvery { capacityRepository.delete(sprintId, profileId) } just Runs

        assertNull(OutboundMessageIdServiceImpl(messageRepository).resolve(messageId))
        assertTrue(TaskWatcherServiceImpl(watcherRepository, mockk(relaxed = true)).listForProfile(profileId).isEmpty())
        val capacityService = CapacityServiceImpl(capacityRepository, workRepository)
        assertEquals(listOf(capacity), capacityService.listForSprint(sprintId))
        capacityService.deleteCommitment(sprintId, profileId)

        coVerify(exactly = 1) { capacityRepository.delete(sprintId, profileId) }
    }

    @Test
    fun `capacity report includes commitment-only and plan-only profiles`() = runTest {
        val capacityRepository = mockk<CapacityRepository>()
        val workRepository = mockk<SprintWorkRepository>()
        val sprintId = UUID.random()
        val commitmentOnlyProfileId = UUID.random()
        val planOnlyProfileId = UUID.random()
        coEvery { capacityRepository.listForSprint(sprintId) } returns listOf(
            Capacity(sprintId, commitmentOnlyProfileId, committedSeconds = 100),
        )
        coEvery { workRepository.aggregateForSprint(sprintId) } returns listOf(
            SprintAssigneeAggregate(planOnlyProfileId, plannedSeconds = 120, completedSeconds = 50),
        )

        val report = CapacityServiceImpl(capacityRepository, workRepository).report(sprintId)

        assertEquals(2, report.size)
        assertEquals(planOnlyProfileId, report[0].profileId)
        assertEquals(0, report[0].committedSeconds)
        assertEquals(120, report[0].plannedSeconds)
        assertEquals(50, report[0].completedSeconds)
        assertEquals(120, report[0].overCommitSeconds)
        assertEquals(commitmentOnlyProfileId, report[1].profileId)
        assertEquals(100, report[1].committedSeconds)
        assertEquals(0, report[1].plannedSeconds)
        assertEquals(0, report[1].completedSeconds)
        assertEquals(0, report[1].overCommitSeconds)
    }
}
