package bosca.workops.service

import bosca.search.IndexStorageSystem
import bosca.search.model.RawSearchResult
import bosca.search.service.SearchService
import bosca.serialization.UUID
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.ai.TriageSuggestion
import bosca.workops.model.task.Task
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.TaskRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiSuggestionServiceTest {

    @Test
    fun `duplicate suggestions return empty when no named search system exists`() = runTest {
        val searchService = mockk<SearchService>()
        val storageSystemService = mockk<StorageSystemService>()
        val optIn = mockk<AiOptInService>()
        val projectId = UUID.random()
        coEvery { optIn.requireEnabled(projectId = projectId, taskId = null) } just Runs
        coEvery { storageSystemService.getAll() } returns listOf(
            storageSystem("supplementary", StorageSystemType.SUPPLEMENTARY),
            storageSystem("", StorageSystemType.SEARCH),
        )

        val suggestions = DuplicateSuggestionServiceImpl(searchService, storageSystemService, optIn)
            .suggest("duplicate", projectId, limit = 10)

        assertTrue(suggestions.isEmpty())
        coVerify(exactly = 0) { searchService.searchRaw(any()) }
    }

    @Test
    fun `duplicate suggestions discard incomplete hits and score empty text safely`() = runTest {
        val searchService = mockk<SearchService>()
        val storageSystemService = mockk<StorageSystemService>()
        val optIn = mockk<AiOptInService>()
        val projectId = UUID.random()
        val candidateId = UUID.random()
        val describedCandidateId = UUID.random()
        val system = storageSystem("workops-search", StorageSystemType.SEARCH)
        coEvery { optIn.requireEnabled(projectId = projectId, taskId = null) } just Runs
        coEvery { storageSystemService.getAll() } returns listOf(system)
        coEvery { searchService.searchRaw(any()) } returns RawSearchResult(
            hits = listOf(
                JsonObject(emptyMap()),
                buildJsonObject {
                    put("id", JsonPrimitive(UUID.random().toString()))
                },
                buildJsonObject {
                    put("id", JsonPrimitive(UUID.random().toString()))
                    put("key", JsonPrimitive("WO-41"))
                },
                buildJsonObject {
                    put("id", JsonPrimitive(candidateId.toString()))
                    put("key", JsonPrimitive("WO-42"))
                    put("summary", JsonPrimitive(""))
                },
                buildJsonObject {
                    put("id", JsonPrimitive(describedCandidateId.toString()))
                    put("key", JsonPrimitive("WO-43"))
                    put("summary", JsonPrimitive("Nonempty summary"))
                    put("descriptionPlain", JsonPrimitive("Additional context"))
                },
            ),
            facets = emptyList(),
            estimatedHits = 5,
            system = IndexStorageSystem(system.id, system.name),
        )

        val suggestions = DuplicateSuggestionServiceImpl(searchService, storageSystemService, optIn)
            .suggest("", projectId, limit = 0)

        assertEquals(2, suggestions.size)
        assertEquals(
            setOf(candidateId, describedCandidateId),
            suggestions.map { it.candidateTaskId }.toSet(),
        )
        assertTrue(suggestions.all { it.lexicalScore == 0.0 })
    }

    @Test
    fun `triage suggestions report missing tasks and pending phase operations`() = runTest {
        val taskRepository = mockk<TaskRepository>()
        val projectRepository = mockk<ProjectRepository>()
        val optIn = mockk<AiOptInService>()
        val missingId = UUID.random()
        val taskId = UUID.random()
        val projectId = UUID.random()
        val task = mockk<Task>()
        every { task.projectId } returns projectId
        coEvery { taskRepository.getById(missingId) } returns null
        coEvery { taskRepository.getById(taskId) } returns task
        coEvery { optIn.requireEnabled(projectId = projectId, taskId = taskId) } just Runs
        val service = TriageSuggestionServiceImpl(taskRepository, projectRepository, optIn)

        val missing = assertFailsWith<WorkOpsNotFoundException> {
            service.suggest(missingId)
        }
        val suggestPending = assertFailsWith<PendingPhaseImplementationException> {
            service.suggest(taskId)
        }
        val acceptPending = assertFailsWith<PendingPhaseImplementationException> {
            service.acceptTriageSuggestion(
                taskId,
                TriageSuggestion(reasoning = "Move to the platform queue"),
                UUID.random(),
            )
        }

        assertEquals("Task", missing.type)
        assertEquals(missingId.toString(), missing.handle)
        assertEquals("TriageSuggestionService.suggest", suggestPending.variant)
        assertEquals(19, suggestPending.owningPhase)
        assertEquals("TriageSuggestionService.acceptTriageSuggestion", acceptPending.variant)
        assertEquals(19, acceptPending.owningPhase)
    }

    @Test
    fun `summaries report missing tasks and pending thread and sprint operations`() = runTest {
        val taskRepository = mockk<TaskRepository>()
        val optIn = mockk<AiOptInService>()
        val missingId = UUID.random()
        val taskId = UUID.random()
        val projectId = UUID.random()
        val task = mockk<Task>()
        every { task.projectId } returns projectId
        coEvery { taskRepository.getById(missingId) } returns null
        coEvery { taskRepository.getById(taskId) } returns task
        coEvery { optIn.requireEnabled(projectId = projectId, taskId = taskId) } just Runs
        val service = SummarizationServiceImpl(taskRepository, optIn)

        val missing = assertFailsWith<WorkOpsNotFoundException> {
            service.summarizeThread(missingId, "COMMENTS")
        }
        val threadPending = assertFailsWith<PendingPhaseImplementationException> {
            service.summarizeThread(taskId, "COMMENTS")
        }
        val sprintPending = assertFailsWith<PendingPhaseImplementationException> {
            service.summarizeSprint(UUID.random())
        }

        assertEquals("Task", missing.type)
        assertEquals(missingId.toString(), missing.handle)
        assertEquals("SummarizationService.summarizeThread", threadPending.variant)
        assertEquals(19, threadPending.owningPhase)
        assertEquals("SummarizationService.summarizeSprint", sprintPending.variant)
        assertEquals(19, sprintPending.owningPhase)
    }

    private fun storageSystem(name: String, type: StorageSystemType) = StorageSystem(
        id = UUID.random(),
        name = name,
        description = "",
        type = type,
        configuration = JsonObject(emptyMap()),
    )
}
