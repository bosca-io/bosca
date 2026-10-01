package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.sla.SlaGoal
import bosca.workops.model.sla.SlaPolicy
import bosca.workops.model.sla.WorkingCalendar
import bosca.workops.repository.SlaGoalRepository
import bosca.workops.repository.SlaPolicyRepository
import bosca.workops.repository.WorkingCalendarRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlaServiceTest {

    @Test
    fun `working calendar service delegates reads and persists validated inputs`() = runTest {
        val repository = mockk<WorkingCalendarRepository>()
        val service = WorkingCalendarServiceImpl(repository)
        val id = UUID.random()
        val calendar = WorkingCalendar(id = id, name = "Support", timeZone = "America/Chicago")
        val input = WorkingCalendarInput(
            name = "Support",
            description = "Business hours",
            timeZone = "America/Chicago",
            weeklyHours = "{}",
            holidays = "[]",
        )
        coEvery { repository.getById(id) } returns calendar
        coEvery { repository.listAll() } returns listOf(calendar)
        coEvery {
            repository.add(input.name, input.description, input.timeZone, input.weeklyHours, input.holidays)
        } returns calendar

        assertEquals(calendar, service.getById(id))
        assertEquals(listOf(calendar), service.list())
        assertEquals(calendar, service.create(input))
    }

    @Test
    fun `working calendar service rejects an invalid timezone before persistence`() = runTest {
        val repository = mockk<WorkingCalendarRepository>()
        val service = WorkingCalendarServiceImpl(repository)
        val input = WorkingCalendarInput("Broken", null, "Mars/Olympus", "{}", "[]")

        val failure = assertFailsWith<WorkOpsValidationException> { service.create(input) }

        assertEquals("timeZone", failure.field)
        coVerify(exactly = 0) { repository.add(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sla policy service delegates policy and goal operations`() = runTest {
        val policies = mockk<SlaPolicyRepository>()
        val goals = mockk<SlaGoalRepository>()
        val service = SlaPolicyServiceImpl(policies, goals)
        val policy = SlaPolicy(id = UUID.random(), name = "Default")
        val goal = SlaGoal(
            id = UUID.random(),
            policyId = policy.id,
            name = "Resolution",
            startConditions = "open",
            stopConditions = "done",
            targetMinutes = 240,
        )
        coEvery { policies.getById(policy.id) } returns policy
        coEvery { policies.listAll() } returns listOf(policy)
        coEvery { policies.add("Default", "Primary policy") } returns policy
        coEvery { goals.listForPolicy(policy.id) } returns listOf(goal)
        coEvery { goals.add(goal) } returns goal

        assertEquals(policy, service.getById(policy.id))
        assertEquals(listOf(policy), service.list())
        assertEquals(policy, service.create("Default", "Primary policy"))
        assertEquals(listOf(goal), service.goalsFor(policy.id))
        assertEquals(goal, service.addGoal(goal))
    }
}
