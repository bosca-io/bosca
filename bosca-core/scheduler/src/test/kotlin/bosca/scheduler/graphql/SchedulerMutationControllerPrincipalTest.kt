package bosca.scheduler.graphql

import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobPrincipalState
import bosca.scheduler.service.SchedulerService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SchedulerMutationControllerPrincipalTest {

    private val schedulerService = mockk<SchedulerService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val controller = SchedulerMutationController(schedulerService, groupEvaluator)
    private val authentication = mockk<AuthenticationContext>()
    private val actorId = UUID.random()
    private val actor = AuthenticatedPrincipal(Principal(id = actorId), emptyList())

    @Test
    fun `admin assignment is immediately confirmed by the assigning admin`() = runBlocking {
        val job = scheduledJob(ScheduledJobPrincipalState.ACTIVE, UUID.random())
        every { authentication.principal() } returns actor
        coEvery {
            schedulerService.assignExecutionPrincipal(job.id, job.executionPrincipalId!!, actorId, actorId)
        } returns job

        assertSame(job, controller.assignExecutionPrincipal(authentication, job.id, job.executionPrincipalId!!))

        coVerify { schedulerService.assignExecutionPrincipal(job.id, job.executionPrincipalId!!, actorId, actorId) }
    }

    @Test
    fun `assignment requires an authenticated admin and existing scheduler row`() = runBlocking {
        every { authentication.principal() } returns null
        assertFailsWith<IllegalStateException> {
            controller.assignExecutionPrincipal(authentication, UUID.random(), UUID.random())
        }

        every { authentication.principal() } returns actor
        coEvery { schedulerService.assignExecutionPrincipal(any(), any(), any(), any()) } returns null
        assertFailsWith<IllegalArgumentException> {
            controller.assignExecutionPrincipal(authentication, UUID.random(), UUID.random())
        }
        Unit
    }

    @Test
    fun `assigned principal or admin can confirm pending assignment`() = runBlocking {
        val pending = scheduledJob(ScheduledJobPrincipalState.PENDING_CONFIRMATION, actorId)
        val active = pending.copy(principalState = ScheduledJobPrincipalState.ACTIVE, principalConfirmedBy = actorId)
        every { authentication.principal() } returns actor
        coEvery { schedulerService.getJob(pending.id) } returns pending
        coEvery { schedulerService.confirmExecutionPrincipal(pending.id, actorId) } returns active

        assertSame(active, controller.confirmExecutionPrincipal(authentication, pending.id))

        val adminActor = AuthenticatedPrincipal(Principal(id = UUID.random()), emptyList())
        every { authentication.principal() } returns adminActor
        every { groupEvaluator.hasAdminGroup(authentication) } returns true
        coEvery { schedulerService.confirmExecutionPrincipal(pending.id, adminActor.id) } returns active
        assertSame(active, controller.confirmExecutionPrincipal(authentication, pending.id))
    }

    @Test
    fun `unrelated principal cannot confirm and missing jobs fail clearly`() = runBlocking {
        val other = AuthenticatedPrincipal(Principal(id = UUID.random()), emptyList())
        val pending = scheduledJob(ScheduledJobPrincipalState.PENDING_CONFIRMATION, actorId)
        every { authentication.principal() } returns other
        coEvery { schedulerService.getJob(pending.id) } returns pending
        every { groupEvaluator.hasAdminGroup(authentication) } returns false

        assertFailsWith<SecurityException> {
            controller.confirmExecutionPrincipal(authentication, pending.id)
        }

        coEvery { schedulerService.getJob(pending.id) } returns null
        assertFailsWith<IllegalArgumentException> {
            controller.confirmExecutionPrincipal(authentication, pending.id)
        }
        Unit
    }

    @Test
    fun `confirmation requires authentication and a surviving pending row`() = runBlocking {
        val pending = scheduledJob(ScheduledJobPrincipalState.PENDING_CONFIRMATION, actorId)
        every { authentication.principal() } returns null
        assertFailsWith<IllegalStateException> {
            controller.confirmExecutionPrincipal(authentication, pending.id)
        }

        every { authentication.principal() } returns actor
        coEvery { schedulerService.getJob(pending.id) } returns pending
        coEvery { schedulerService.confirmExecutionPrincipal(pending.id, actorId) } returns null
        assertFailsWith<IllegalArgumentException> {
            controller.confirmExecutionPrincipal(authentication, pending.id)
        }
        Unit
    }

    @Test
    fun `admin clear delegates to scheduler service`() = runBlocking {
        val job = scheduledJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL, null)
        coEvery { schedulerService.clearExecutionPrincipal(job.id) } returns job

        assertSame(job, controller.clearExecutionPrincipal(authentication, job.id))
        coVerify { schedulerService.clearExecutionPrincipal(job.id) }
    }

    @Test
    fun `admin clear fails when the row disappeared`() = runBlocking {
        coEvery { schedulerService.clearExecutionPrincipal(any()) } returns null

        assertFailsWith<IllegalArgumentException> {
            controller.clearExecutionPrincipal(authentication, UUID.random())
        }
        Unit
    }

    private fun scheduledJob(state: ScheduledJobPrincipalState, principalId: UUID?) = ScheduledJob(
        id = UUID.random(),
        name = "job",
        jobName = "test",
        cronExpression = "0 * * * *",
        enabled = state == ScheduledJobPrincipalState.ACTIVE,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        createdBy = actorId,
        executionPrincipalId = principalId,
        principalState = state,
        principalAssignedBy = principalId,
    )
}
