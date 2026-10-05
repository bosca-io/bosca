package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.task.CreatePriorityInput
import bosca.workops.model.task.CreateResolutionInput
import bosca.workops.model.task.CreateTaskTypeInput
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Resolution
import bosca.workops.model.task.TaskHierarchyLevel
import bosca.workops.model.task.TaskType
import bosca.workops.model.task.TaskTypeScheme
import bosca.workops.model.task.UpdatePriorityInput
import bosca.workops.model.task.UpdateResolutionInput
import bosca.workops.model.task.UpdateTaskTypeInput
import bosca.workops.model.workflow.CreateStatusInput
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.model.workflow.UpdateStatusInput
import bosca.workops.repository.PriorityRepository
import bosca.workops.repository.ResolutionRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.TaskTypeRepository
import bosca.workops.repository.TaskTypeSchemeRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LookupServicesTest {

    @Test
    fun `task type scheme service short-circuits empty batches and delegates populated lookups`() = runTest {
        val repository = mockk<TaskTypeSchemeRepository>()
        val taskTypeId = UUID.random()
        val scheme = TaskTypeScheme(
            id = UUID.random(),
            name = "Default",
            taskTypeIds = listOf(taskTypeId),
            defaultTaskTypeId = taskTypeId,
        )
        coEvery { repository.getAll() } returns listOf(scheme)
        coEvery { repository.getById(scheme.id) } returns scheme
        coEvery { repository.getByIds(listOf(scheme.id)) } returns listOf(scheme)
        val service = TaskTypeSchemeServiceImpl(repository)

        assertEquals(listOf(scheme), service.list())
        assertEquals(scheme, service.getById(scheme.id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(scheme), service.getByIds(listOf(scheme.id)))

        coVerify(exactly = 0) { repository.getByIds(emptyList()) }
    }

    @Test
    fun `task type service maps inputs and covers collection and optimistic update outcomes`() = runTest {
        val repository = mockk<TaskTypeRepository>()
        val id = UUID.random()
        val missingId = UUID.random()
        val existing = TaskType(id, "Task", "work", "task", "#112233")
        val createInput = CreateTaskTypeInput("Epic", "large work", "epic", "#445566", TaskHierarchyLevel.EPIC)
        val created = TaskType(UUID.random(), "Epic", "large work", "epic", "#445566", TaskHierarchyLevel.EPIC)
        val updateInput = UpdateTaskTypeInput("Story", "small work", "story", "#778899", TaskHierarchyLevel.STANDARD, 3)
        val updated = TaskType(id, "Story", "small work", "story", "#778899", version = 4)
        coEvery { repository.getAll() } returns listOf(existing)
        coEvery { repository.getById(id) } returns existing
        coEvery { repository.getByIds(listOf(id)) } returns listOf(existing)
        coEvery {
            repository.add(TaskType(name = "Epic", description = "large work", iconKey = "epic", colorHex = "#445566", hierarchyLevel = TaskHierarchyLevel.EPIC))
        } returns created
        coEvery { repository.update(id, "Story", "small work", "story", "#778899", TaskHierarchyLevel.STANDARD, 3) } returns updated
        coEvery { repository.update(missingId, "Story", "small work", "story", "#778899", TaskHierarchyLevel.STANDARD, 3) } returns null
        coEvery { repository.deleteById(id) } just Runs
        val service = TaskTypeServiceImpl(repository)

        assertEquals(listOf(existing), service.list())
        assertEquals(existing, service.getById(id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(existing), service.getByIds(listOf(id)))
        assertEquals(created, service.create(createInput))
        assertEquals(updated, service.update(id, updateInput))
        assertFailsWith<IllegalStateException> { service.update(missingId, updateInput) }
        service.delete(id)

        coVerify(exactly = 1) { repository.deleteById(id) }
    }

    @Test
    fun `status service maps inputs and covers collection and optimistic update outcomes`() = runTest {
        val repository = mockk<StatusRepository>()
        val id = UUID.random()
        val missingId = UUID.random()
        val existing = Status(id, "Todo", "queued", StatusCategory.TODO, "#112233")
        val createInput = CreateStatusInput("Doing", "active", StatusCategory.IN_PROGRESS, "#445566")
        val created = Status(UUID.random(), "Doing", "active", StatusCategory.IN_PROGRESS, "#445566")
        val updateInput = UpdateStatusInput("Done", "finished", StatusCategory.DONE, "#778899", 3)
        val updated = Status(id, "Done", "finished", StatusCategory.DONE, "#778899", version = 4)
        coEvery { repository.getAll() } returns listOf(existing)
        coEvery { repository.getById(id) } returns existing
        coEvery { repository.getByIds(listOf(id)) } returns listOf(existing)
        coEvery {
            repository.add(Status(name = "Doing", description = "active", category = StatusCategory.IN_PROGRESS, colorHex = "#445566"))
        } returns created
        coEvery { repository.update(id, "Done", "finished", StatusCategory.DONE, "#778899", 3) } returns updated
        coEvery { repository.update(missingId, "Done", "finished", StatusCategory.DONE, "#778899", 3) } returns null
        coEvery { repository.deleteById(id) } just Runs
        val service = StatusServiceImpl(repository)

        assertEquals(listOf(existing), service.list())
        assertEquals(existing, service.getById(id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(existing), service.getByIds(listOf(id)))
        assertEquals(created, service.create(createInput))
        assertEquals(updated, service.update(id, updateInput))
        assertFailsWith<IllegalStateException> { service.update(missingId, updateInput) }
        service.delete(id)

        coVerify(exactly = 1) { repository.deleteById(id) }
    }

    @Test
    fun `priority service maps inputs and covers collection and optimistic update outcomes`() = runTest {
        val repository = mockk<PriorityRepository>()
        val id = UUID.random()
        val missingId = UUID.random()
        val existing = Priority(id, "Medium", "normal", "medium", "#112233", 2)
        val createInput = CreatePriorityInput("High", "urgent", "high", "#445566", 3)
        val created = Priority(UUID.random(), "High", "urgent", "high", "#445566", 3)
        val updateInput = UpdatePriorityInput("Critical", "immediate", "critical", "#778899", 4, 3)
        val updated = Priority(id, "Critical", "immediate", "critical", "#778899", 4, version = 4)
        coEvery { repository.getAll() } returns listOf(existing)
        coEvery { repository.getById(id) } returns existing
        coEvery { repository.getByIds(listOf(id)) } returns listOf(existing)
        coEvery {
            repository.add(Priority(name = "High", description = "urgent", iconKey = "high", colorHex = "#445566", displayOrder = 3))
        } returns created
        coEvery { repository.update(id, "Critical", "immediate", "critical", "#778899", 4, 3) } returns updated
        coEvery { repository.update(missingId, "Critical", "immediate", "critical", "#778899", 4, 3) } returns null
        coEvery { repository.deleteById(id) } just Runs
        val service = PriorityServiceImpl(repository)

        assertEquals(listOf(existing), service.list())
        assertEquals(existing, service.getById(id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(existing), service.getByIds(listOf(id)))
        assertEquals(created, service.create(createInput))
        assertEquals(updated, service.update(id, updateInput))
        assertFailsWith<IllegalStateException> { service.update(missingId, updateInput) }
        service.delete(id)

        coVerify(exactly = 1) { repository.deleteById(id) }
    }

    @Test
    fun `resolution service maps inputs and covers collection and optimistic update outcomes`() = runTest {
        val repository = mockk<ResolutionRepository>()
        val id = UUID.random()
        val missingId = UUID.random()
        val existing = Resolution(id, "Done", "complete", 1)
        val createInput = CreateResolutionInput("Duplicate", "already tracked", 2)
        val created = Resolution(UUID.random(), "Duplicate", "already tracked", 2)
        val updateInput = UpdateResolutionInput("Declined", "not planned", 3, 3)
        val updated = Resolution(id, "Declined", "not planned", 3, version = 4)
        coEvery { repository.getAll() } returns listOf(existing)
        coEvery { repository.getById(id) } returns existing
        coEvery { repository.getByIds(listOf(id)) } returns listOf(existing)
        coEvery {
            repository.add(Resolution(name = "Duplicate", description = "already tracked", displayOrder = 2))
        } returns created
        coEvery { repository.update(id, "Declined", "not planned", 3, 3) } returns updated
        coEvery { repository.update(missingId, "Declined", "not planned", 3, 3) } returns null
        coEvery { repository.deleteById(id) } just Runs
        val service = ResolutionServiceImpl(repository)

        assertEquals(listOf(existing), service.list())
        assertEquals(existing, service.getById(id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(existing), service.getByIds(listOf(id)))
        assertEquals(created, service.create(createInput))
        assertEquals(updated, service.update(id, updateInput))
        assertFailsWith<IllegalStateException> { service.update(missingId, updateInput) }
        service.delete(id)

        coVerify(exactly = 1) { repository.deleteById(id) }
    }
}
