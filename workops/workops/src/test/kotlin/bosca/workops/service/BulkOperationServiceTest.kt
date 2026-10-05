package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.bulk.BulkOperation
import bosca.workops.model.bulk.BulkOperationState
import bosca.workops.model.task.Task
import bosca.workops.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BulkOperationServiceTest {

    private val queryService = mockk<TaskQueryService>()
    private val taskService = mockk<TaskService>()
    private val taskRepository = mockk<TaskRepository>()
    private val service = BulkOperationServiceImpl(queryService, taskService, taskRepository)
    private val principalId = UUID.random()
    private val profileId = UUID.random()

    @Test
    fun `dry run pages through every match without loading or mutating tasks`() = runTest {
        val firstPage = List(500) { index -> task(key = "APP-${index + 1}") }
        val finalPage = listOf(task(key = "APP-501"))
        coEvery { queryService.search("project = APP", profileId, 0, 500) } returns
            TaskSearchResult(firstPage, emptyList())
        coEvery { queryService.search("project = APP", profileId, 500, 500) } returns
            TaskSearchResult(finalPage, emptyList())

        val handle = service.execute(
            "project = APP",
            BulkOperation.AssignTo(profileId),
            principalId,
            profileId,
            dryRun = true,
        )

        assertEquals(501, handle.total)
        assertEquals(0, handle.processed)
        assertEquals(0, handle.errors)
        assertEquals(BulkOperationState.DRY_RUN, handle.state)
        assertEquals(true, handle.dryRun)
        coVerify(exactly = 0) { taskRepository.getActiveById(any()) }
        coVerify(exactly = 0) { taskService.update(any(), any(), any(), any()) }
    }

    @Test
    fun `supported operations update assign and delete active tasks`() = runTest {
        val task = task(version = 7)
        coEvery { taskRepository.getActiveById(task.id) } returns task
        coEvery { taskService.update(task.id, any(), principalId, profileId) } returns task
        coEvery { taskService.softDelete(task.id, 7, principalId, profileId) } returns task
        listOf("summary", "assign", "delete").forEach { query ->
            coEvery { queryService.search(query, profileId, 0, 500) } returns
                TaskSearchResult(listOf(task), emptyList())
        }

        val summary = service.execute(
            "summary",
            BulkOperation.EditField("summary", "Updated summary"),
            principalId,
            profileId,
            dryRun = false,
        )
        val assign = service.execute(
            "assign",
            BulkOperation.AssignTo(UUID.random()),
            principalId,
            profileId,
            dryRun = false,
        )
        val delete = service.execute(
            "delete",
            BulkOperation.Delete,
            principalId,
            profileId,
            dryRun = false,
        )

        listOf(summary, assign, delete).forEach { handle ->
            assertEquals(1, handle.total)
            assertEquals(1, handle.processed)
            assertEquals(0, handle.errors)
            assertEquals(BulkOperationState.COMPLETED, handle.state)
        }
        coVerify(exactly = 2) { taskService.update(task.id, any(), principalId, profileId) }
        coVerify(exactly = 1) { taskService.softDelete(task.id, 7, principalId, profileId) }
    }

    @Test
    fun `a task deleted between search and apply is still processed without mutation`() = runTest {
        val task = task()
        coEvery { queryService.search("gone", null, 0, 500) } returns TaskSearchResult(listOf(task), emptyList())
        coEvery { taskRepository.getActiveById(task.id) } returns null

        val handle = service.execute(
            "gone",
            BulkOperation.AssignTo(UUID.random()),
            principalId,
            actingProfileId = null,
            dryRun = false,
        )

        assertEquals(1, handle.processed)
        assertEquals(BulkOperationState.COMPLETED, handle.state)
        coVerify(exactly = 0) { taskService.update(any(), any(), any(), any()) }
    }

    @Test
    fun `non dry run advances to the next page`() = runTest {
        val task = task()
        coEvery { queryService.search("paged", profileId, 0, 500) } returns
            TaskSearchResult(List(500) { task }, emptyList())
        coEvery { queryService.search("paged", profileId, 500, 500) } returns
            TaskSearchResult(emptyList(), emptyList())
        coEvery { taskRepository.getActiveById(task.id) } returns null

        val handle = service.execute(
            "paged",
            BulkOperation.AssignTo(UUID.random()),
            principalId,
            profileId,
            dryRun = false,
        )

        assertEquals(500, handle.total)
        assertEquals(500, handle.processed)
        assertEquals(BulkOperationState.COMPLETED, handle.state)
    }

    @Test
    fun `unsupported variants produce partial handles with useful error details`() = runTest {
        val task = task()
        val operations = listOf(
            BulkOperation.EditField("priority", "High"),
            BulkOperation.Transition(UUID.random()),
            BulkOperation.AddLabel(UUID.random()),
            BulkOperation.RemoveLabel(UUID.random()),
        )
        operations.forEachIndexed { index, operation ->
            val query = "unsupported-$index"
            coEvery { queryService.search(query, profileId, 0, 500) } returns
                TaskSearchResult(listOf(task), emptyList())
            coEvery { taskRepository.getActiveById(task.id) } returns task

            val handle = service.execute(query, operation, principalId, profileId, dryRun = false)

            assertEquals(1, handle.total)
            assertEquals(0, handle.processed)
            assertEquals(1, handle.errors)
            assertEquals(BulkOperationState.PARTIAL, handle.state)
            assertTrue(handle.errorDetails.single().startsWith("task ${task.key}:"))
        }
    }

    @Test
    fun `ordinary failures use the exception type when no message exists`() = runTest {
        val task = task()
        coEvery { queryService.search("failure", profileId, 0, 500) } returns
            TaskSearchResult(listOf(task), emptyList())
        coEvery { taskRepository.getActiveById(task.id) } returns task
        coEvery { taskService.update(task.id, any(), principalId, profileId) } throws MessageLessException()

        val handle = service.execute(
            "failure",
            BulkOperation.EditField("summary", "Updated"),
            principalId,
            profileId,
            dryRun = false,
        )

        assertEquals(listOf("task ${task.key}: MessageLessException"), handle.errorDetails)
    }

    @Test
    fun `cancellation from a task mutation propagates`() = runTest {
        val task = task()
        coEvery { queryService.search("cancel", profileId, 0, 500) } returns
            TaskSearchResult(listOf(task), emptyList())
        coEvery { taskRepository.getActiveById(task.id) } returns task
        coEvery { taskService.update(task.id, any(), principalId, profileId) } throws
            CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            service.execute(
                "cancel",
                BulkOperation.EditField("summary", "Updated"),
                principalId,
                profileId,
                dryRun = false,
            )
        }
    }

    private fun task(
        id: UUID = UUID.random(),
        key: String = "APP-1",
        version: Long = 1,
    ) = Task(
        id = id,
        key = key,
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Summary",
        reporterProfileId = UUID.random(),
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
        version = version,
    )

    private class MessageLessException : RuntimeException()
}
