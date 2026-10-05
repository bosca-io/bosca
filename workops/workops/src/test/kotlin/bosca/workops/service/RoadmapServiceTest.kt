package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.roadmap.RoadmapScenario
import bosca.workops.model.task.Task
import bosca.workops.model.task.UpdateTaskInput
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.repository.RoadmapScenarioRepository
import bosca.workops.repository.RoadmapTaskRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.TaskRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadmapServiceTest {

    private val scenarioRepository = mockk<RoadmapScenarioRepository>()
    private val taskRepository = mockk<TaskRepository>()
    private val roadmapTaskRepository = mockk<RoadmapTaskRepository>()
    private val statusRepository = mockk<StatusRepository>()
    private val taskService = mockk<TaskService>()
    private val json = Json
    private val service = RoadmapServiceImpl(
        scenarioRepository,
        taskRepository,
        roadmapTaskRepository,
        statusRepository,
        taskService,
        json,
    )

    @Test
    fun `scenario CRUD preserves authored JSON and repository results`() = runTest {
        val programId = UUID.random()
        val profileId = UUID.random()
        val overrides = buildJsonObject { put("mode", "optimistic") }
        val scenario = RoadmapScenario(
            id = UUID.random(),
            programId = programId,
            name = "Optimistic",
            description = "Earlier delivery",
            overrides = overrides,
            createdByProfileId = profileId,
        )
        coEvery { scenarioRepository.listForProgram(programId) } returns listOf(scenario)
        coEvery { scenarioRepository.getById(scenario.id) } returns scenario
        coEvery {
            scenarioRepository.add(programId, "Optimistic", "Earlier delivery", "{\"mode\":\"optimistic\"}", profileId)
        } returns scenario
        coEvery { scenarioRepository.delete(scenario.id) } just Runs

        assertEquals(listOf(scenario), service.listScenarios(programId))
        assertEquals(scenario, service.getScenario(scenario.id))
        assertEquals(scenario, service.createScenario(programId, "Optimistic", "Earlier delivery", overrides, profileId))
        service.deleteScenario(scenario.id)
        coVerify { scenarioRepository.delete(scenario.id) }
    }

    @Test
    fun `compute applies valid overlays and safely falls back for malformed values`() = runTest {
        val programId = UUID.random()
        val statusId = UUID.random()
        val assignee = UUID.random()
        val first = task(
            summary = "Original",
            statusId = statusId,
            startDate = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            dueDate = OffsetDateTime.parse("2026-02-01T00:00:00Z"),
            assigneeProfileId = UUID.random(),
            epicChildCount = 4,
            epicChildDoneCount = 3,
        )
        val second = task(summary = "Unchanged", epicChildCount = 0, epicChildDoneCount = 0)
        val third = task(
            summary = "Valid due date",
            dueDate = OffsetDateTime.parse("2026-02-15T00:00:00Z"),
            epicChildCount = 2,
            epicChildDoneCount = 1,
        )
        val overlay = JsonObject(
            mapOf(
                first.id.toString() to buildJsonObject {
                    put("summary", "Scenario summary")
                    put("startDate", "2026-03-01T00:00:00Z")
                    put("dueDate", "not-a-date")
                    put("assigneeProfileId", assignee.toString())
                },
                second.id.toString() to buildJsonObject {
                    put("summary", JsonObject(emptyMap()))
                    put("startDate", "invalid")
                    put("assigneeProfileId", "invalid")
                },
                third.id.toString() to buildJsonObject {
                    put("dueDate", "2026-08-01T00:00:00Z")
                },
            ),
        )
        val scenario = RoadmapScenario(
            id = UUID.random(),
            programId = programId,
            name = "Scenario",
            overrides = overlay,
            createdByProfileId = UUID.random(),
        )
        coEvery { roadmapTaskRepository.listEpicsForProgram(programId) } returns listOf(first, second, third)
        coEvery { scenarioRepository.getById(scenario.id) } returns scenario
        coEvery { statusRepository.getAll() } returns listOf(
            Status(id = statusId, name = "Done", category = StatusCategory.DONE, colorHex = "#0f0"),
        )

        val result = service.compute(programId, scenario.id)
        assertEquals("Scenario summary", result.first().summary)
        assertEquals(OffsetDateTime.parse("2026-03-01T00:00:00Z"), result.first().startDate)
        assertEquals(first.dueDate, result.first().dueDate)
        assertEquals(assignee, result.first().assigneeProfileId)
        assertEquals(75, result.first().progressPercent)
        assertEquals("DONE", result.first().statusCategory)
        assertTrue(result.first().isFromScenario)
        assertEquals("Unchanged", result[1].summary)
        assertEquals(0, result[1].progressPercent)
        assertEquals("TODO", result[1].statusCategory)
        assertNull(result[1].startDate)
        assertNull(result[1].assigneeProfileId)
        assertEquals(OffsetDateTime.parse("2026-08-01T00:00:00Z"), result[2].dueDate)
        assertEquals(50, result[2].progressPercent)

        val nonObject = scenario.copy(overrides = JsonPrimitive("not-an-overlay"))
        coEvery { scenarioRepository.getById(nonObject.id) } returns nonObject
        val baseline = service.compute(programId, nonObject.id)
        assertEquals("Original", baseline.first().summary)
        assertEquals(false, baseline.first().isFromScenario)
    }

    @Test
    fun `compute without a scenario preserves baselines`() = runTest {
        val programId = UUID.random()
        val statusId = UUID.random()
        val task = task(
            summary = "Baseline",
            statusId = statusId,
            startDate = OffsetDateTime.parse("2026-05-01T00:00:00Z"),
            dueDate = OffsetDateTime.parse("2026-06-01T00:00:00Z"),
            assigneeProfileId = UUID.random(),
            epicChildCount = 0,
            epicChildDoneCount = 0,
        )
        coEvery { roadmapTaskRepository.listEpicsForProgram(programId) } returns listOf(task)
        coEvery { statusRepository.getAll() } returns listOf(
            Status(id = statusId, name = "Doing", category = StatusCategory.IN_PROGRESS, colorHex = "#00f"),
        )

        val entry = service.compute(programId, null).single()

        assertEquals("Baseline", entry.summary)
        assertEquals(task.startDate, entry.startDate)
        assertEquals(task.dueDate, entry.dueDate)
        assertEquals(task.assigneeProfileId, entry.assigneeProfileId)
        assertEquals(0, entry.progressPercent)
        assertEquals("IN_PROGRESS", entry.statusCategory)
        assertEquals(false, entry.isFromScenario)
        coVerify(exactly = 0) { scenarioRepository.getById(any()) }
    }

    @Test
    fun `commit ignores malformed entries and applies valid task overrides`() = runTest {
        val scenarioId = UUID.random()
        val principalId = UUID.random()
        val profileId = UUID.random()
        val task = task(summary = "Original", version = 7)
        val missingTaskId = UUID.random()
        val newAssignee = UUID.random()
        val update = slot<UpdateTaskInput>()
        val malformedTask = task(summary = "Malformed", version = 8)
        val scenario = RoadmapScenario(
            id = scenarioId,
            programId = UUID.random(),
            name = "Commit",
            overrides = JsonObject(
                mapOf(
                    "bad-id" to buildJsonObject { put("summary", "ignored") },
                    UUID.random().toString() to JsonPrimitive("not-an-object"),
                    missingTaskId.toString() to buildJsonObject { put("summary", "missing") },
                    task.id.toString() to buildJsonObject {
                        put("summary", "Committed")
                        put("startDate", "2026-04-01T00:00:00Z")
                        put("dueDate", "not-a-date")
                        put("assigneeProfileId", newAssignee.toString())
                    },
                    malformedTask.id.toString() to buildJsonObject {
                        put("summary", JsonObject(emptyMap()))
                        put("startDate", "not-a-date")
                    },
                ),
            ),
            createdByProfileId = profileId,
        )
        coEvery { scenarioRepository.getById(scenarioId) } returns scenario
        coEvery { taskRepository.getActiveById(missingTaskId) } returns null
        coEvery { taskRepository.getActiveById(task.id) } returns task
        coEvery { taskRepository.getActiveById(malformedTask.id) } returns malformedTask
        coEvery { taskService.update(task.id, capture(update), principalId, profileId) } returns task.copy(summary = "Committed")
        coEvery { taskService.update(malformedTask.id, any(), principalId, profileId) } returns malformedTask

        assertEquals(2, service.commitScenario(scenarioId, principalId, profileId))
        assertEquals("Committed", update.captured.summary)
        assertEquals(OffsetDateTime.parse("2026-04-01T00:00:00Z"), update.captured.startDate)
        assertNull(update.captured.dueDate)
        assertEquals(newAssignee, update.captured.assigneeProfileId)
        assertEquals(7, update.captured.expectedVersion)

        val missingScenario = UUID.random()
        coEvery { scenarioRepository.getById(missingScenario) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.commitScenario(missingScenario, principalId, null) }

        val nonObject = scenario.copy(id = UUID.random(), overrides = JsonPrimitive("none"))
        coEvery { scenarioRepository.getById(nonObject.id) } returns nonObject
        assertEquals(0, service.commitScenario(nonObject.id, principalId, null))
    }

    @Test
    fun `commit preserves omitted fields while parsing valid dates and rejecting malformed assignees`() = runTest {
        val scenarioId = UUID.random()
        val principalId = UUID.random()
        val task = task(
            summary = "Original",
            startDate = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            dueDate = OffsetDateTime.parse("2026-02-01T00:00:00Z"),
            assigneeProfileId = UUID.random(),
            version = 9,
        )
        val update = slot<UpdateTaskInput>()
        coEvery { scenarioRepository.getById(scenarioId) } returns RoadmapScenario(
            id = scenarioId,
            programId = UUID.random(),
            name = "Dates",
            overrides = buildJsonObject {
                putJsonObject(task.id.toString()) {
                    put("dueDate", "2026-07-01T00:00:00Z")
                    put("assigneeProfileId", "not-a-uuid")
                }
            },
            createdByProfileId = UUID.random(),
        )
        coEvery { taskRepository.getActiveById(task.id) } returns task
        coEvery { taskService.update(task.id, capture(update), principalId, null) } returns task

        assertEquals(1, service.commitScenario(scenarioId, principalId, null))
        with(update.captured) {
            assertEquals("Original", summary)
            assertNull(startDate)
            assertEquals(OffsetDateTime.parse("2026-07-01T00:00:00Z"), dueDate)
            assertNull(assigneeProfileId)
            assertEquals(9, expectedVersion)
        }
    }

    private fun task(
        summary: String,
        statusId: UUID = UUID.random(),
        startDate: OffsetDateTime? = null,
        dueDate: OffsetDateTime? = null,
        assigneeProfileId: UUID? = null,
        epicChildCount: Int = 0,
        epicChildDoneCount: Int = 0,
        version: Long = 1,
    ) = Task(
        id = UUID.random(),
        key = "ROAD-1",
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = statusId,
        priorityId = UUID.random(),
        summary = summary,
        assigneeProfileId = assigneeProfileId,
        reporterProfileId = UUID.random(),
        startDate = startDate,
        dueDate = dueDate,
        epicChildCount = epicChildCount,
        epicChildDoneCount = epicChildDoneCount,
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
        version = version,
    )
}
