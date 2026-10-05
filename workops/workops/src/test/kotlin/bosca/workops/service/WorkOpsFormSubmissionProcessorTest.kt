package bosca.workops.service

import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaType
import bosca.forms.model.FormSubmissionInput
import bosca.serialization.UUID
import bosca.workops.model.project.Project
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.task.Task
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkOpsFormSubmissionProcessorTest {

    private val taskService = mockk<TaskService>()
    private val projectService = mockk<ProjectService>()
    private val processor = WorkOpsFormSubmissionProcessor(taskService, projectService)

    private val projectId = UUID.random()
    private val profileId = UUID.random()
    private val schemaId = UUID.random()

    private val project = Project(
        id = projectId,
        programId = UUID.random(),
        key = "TEST",
        name = "Test Project",
        ownerProfileId = profileId,
    )

    private fun schema(config: JsonElement?) = FormSchema(
        id = schemaId,
        type = FormSchemaType.WORK_OPS,
        key = "workops-form",
        name = "Work Ops Form",
        description = "",
        schema = buildJsonObject { put("type", "object") },
        uiSchema = buildJsonObject { put("version", 1) },
        configuration = config,
        version = 1,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    private fun submissionInput(attrs: JsonObject) = FormSubmissionInput(
        formSchemaId = schemaId,
        attributes = attrs,
    )

    @Test
    fun `type is WORK_OPS`() {
        assertEquals(FormSchemaType.WORK_OPS, processor.type)
    }

    @Test
    fun `process creates task from submission attributes`() = runTest {
        coEvery { projectService.getById(projectId) } returns project
        val taskSlot = slot<CreateTaskInput>()
        coEvery { taskService.create(capture(taskSlot), any(), any(), any()) } returns mockk<Task>(relaxed = true)

        val config = buildJsonObject {
            putJsonObject("workOps") {
                put("projectId", projectId.toString())
                putJsonObject("fieldMappings") {
                    put("summary", "title")
                    put("descriptionMarkdown", "body")
                }
            }
        }
        val attrs = buildJsonObject {
            put("title", "Bug in login")
            put("body", "Steps to reproduce...")
            put("severity", "high")
        }

        processor.process(profileId, submissionInput(attrs), schema(config))

        val input = taskSlot.captured
        assertEquals(projectId, input.projectId)
        assertEquals("Bug in login", input.summary)
        assertEquals("Steps to reproduce...", input.descriptionMarkdown)
        assertEquals(JsonPrimitive("high"), input.customFields["severity"])
    }

    @Test
    fun `process throws when no workOps configuration block`() = runTest {
        val config = buildJsonObject { put("other", "data") }
        assertFailsWith<IllegalStateException> {
            processor.process(profileId, submissionInput(buildJsonObject {}), schema(config))
        }
        coVerify(exactly = 0) { taskService.create(any(), any(), any(), any()) }
    }

    @Test
    fun `process throws when projectId missing`() = runTest {
        val config = buildJsonObject {
            putJsonObject("workOps") {}
        }
        assertFailsWith<IllegalStateException> {
            processor.process(profileId, submissionInput(buildJsonObject {}), schema(config))
        }
        coVerify(exactly = 0) { taskService.create(any(), any(), any(), any()) }
    }

    @Test
    fun `process throws when summary missing`() = runTest {
        coEvery { projectService.getById(projectId) } returns null
        val config = buildJsonObject {
            putJsonObject("workOps") {
                put("projectId", projectId.toString())
            }
        }
        assertFailsWith<IllegalStateException> {
            processor.process(
                profileId,
                submissionInput(buildJsonObject {}),
                schema(config),
            )
        }
        coVerify(exactly = 0) { taskService.create(any(), any(), any(), any()) }
    }

    @Test
    fun `process uses default field keys when no mappings`() = runTest {
        coEvery { projectService.getById(projectId) } returns project
        val taskSlot = slot<CreateTaskInput>()
        coEvery { taskService.create(capture(taskSlot), any(), any(), any()) } returns mockk<Task>(relaxed = true)

        val config = buildJsonObject {
            putJsonObject("workOps") {
                put("projectId", projectId.toString())
            }
        }
        val attrs = buildJsonObject {
            put("summary", "Default mapping test")
            put("descriptionMarkdown", "Some description")
        }

        processor.process(profileId, submissionInput(attrs), schema(config))

        assertEquals("Default mapping test", taskSlot.captured.summary)
        assertEquals("Some description", taskSlot.captured.descriptionMarkdown)
    }

    @Test
    fun `process maps configured and submitted optional fields while retaining only custom attributes`() = runTest {
        val taskTypeId = UUID.random()
        val ignoredSubmittedTaskTypeId = UUID.random()
        val statusId = UUID.random()
        val assigneeProfileId = UUID.random()
        val createdTaskId = UUID.random()
        coEvery { projectService.getById(projectId) } returns project
        val taskSlot = slot<CreateTaskInput>()
        coEvery { taskService.create(capture(taskSlot), profileId, profileId, profileId) } returns mockk {
            every { id } returns createdTaskId
        }

        val config = buildJsonObject {
            putJsonObject("workOps") {
                put("projectId", projectId.toString())
                put("taskTypeId", taskTypeId.toString())
                putJsonObject("fieldMappings") {
                    put("summary", "title")
                    put("descriptionMarkdown", "details")
                    put("taskTypeId", "submittedTaskType")
                    put("statusId", "submittedStatus")
                    put("priorityId", "submittedPriority")
                    put("assigneeProfileId", "submittedAssignee")
                    put("dueDate", "submittedDue")
                    put("startDate", "submittedStart")
                }
            }
        }
        val attrs = buildJsonObject {
            put("title", "Ship the release")
            put("details", "Coordinate the production rollout")
            put("submittedTaskType", ignoredSubmittedTaskTypeId.toString())
            put("submittedStatus", statusId.toString())
            put("submittedPriority", "")
            put("submittedAssignee", assigneeProfileId.toString())
            put("submittedDue", "2026-08-21T17:00:00Z")
            put("submittedStart", "")
            put("customerImpact", "high")
        }

        val submitted = processor.process(profileId, submissionInput(attrs), schema(config))

        assertEquals(createdTaskId, submitted.id)
        assertEquals(FormSchemaType.WORK_OPS, submitted.type)
        with(taskSlot.captured) {
            assertEquals(projectId, this.projectId)
            assertEquals(taskTypeId, this.taskTypeId)
            assertEquals(statusId, this.statusId)
            assertNull(priorityId)
            assertEquals(assigneeProfileId, this.assigneeProfileId)
            assertEquals(OffsetDateTime.parse("2026-08-21T17:00:00Z"), dueDate)
            assertNull(startDate)
            assertEquals(mapOf("customerImpact" to JsonPrimitive("high")), customFields)
        }
    }

    @Test
    fun `process accepts project from attributes and still creates when the project is not yet resolvable`() = runTest {
        val createdTaskId = UUID.random()
        coEvery { projectService.getById(projectId) } returns null
        val taskSlot = slot<CreateTaskInput>()
        coEvery { taskService.create(capture(taskSlot), profileId, profileId, profileId) } returns mockk {
            every { id } returns createdTaskId
        }
        val config = buildJsonObject { putJsonObject("workOps") {} }
        val attrs = buildJsonObject {
            put("projectId", projectId.toString())
            put("summary", "Create before project cache refresh")
        }

        val submitted = processor.process(profileId, submissionInput(attrs), schema(config))

        assertEquals(createdTaskId, submitted.id)
        assertEquals(projectId, taskSlot.captured.projectId)
        assertTrue(taskSlot.captured.customFields.isEmpty())
    }

    @Test
    fun `process rejects a null configuration and a blank mapped summary`() = runTest {
        assertFailsWith<IllegalStateException> {
            processor.process(profileId, submissionInput(buildJsonObject {}), schema(null))
        }

        coEvery { projectService.getById(projectId) } returns project
        val config = buildJsonObject {
            putJsonObject("workOps") {
                put("projectId", projectId.toString())
                putJsonObject("fieldMappings") { put("summary", "title") }
            }
        }
        val failure = assertFailsWith<IllegalStateException> {
            processor.process(
                profileId,
                submissionInput(buildJsonObject { put("title", "   ") }),
                schema(config),
            )
        }

        assertTrue("title" in (failure.message ?: ""))
        coVerify(exactly = 0) { taskService.create(any(), any(), any(), any()) }
    }

    @Test
    fun `process rejects structurally invalid configuration and mapped values`() = runTest {
        coEvery { projectService.getById(projectId) } returns project

        val malformedInputs = listOf(
            schema(buildJsonObject { put("workOps", JsonPrimitive("invalid")) }) to buildJsonObject {},
            schema(buildJsonObject {
                putJsonObject("workOps") {
                    put("projectId", buildJsonObject {})
                }
            }) to buildJsonObject { put("summary", "Task") },
            schema(buildJsonObject {
                putJsonObject("workOps") {
                    put("projectId", projectId.toString())
                    put("fieldMappings", JsonPrimitive("invalid"))
                }
            }) to buildJsonObject { put("summary", "Task") },
            schema(buildJsonObject {
                putJsonObject("workOps") {
                    put("projectId", projectId.toString())
                    putJsonObject("fieldMappings") { put("summary", buildJsonObject {}) }
                }
            }) to buildJsonObject { put("summary", "Task") },
            schema(buildJsonObject {
                putJsonObject("workOps") { put("projectId", projectId.toString()) }
            }) to buildJsonObject { put("summary", buildJsonObject {}) },
            schema(buildJsonObject {
                putJsonObject("workOps") { put("projectId", projectId.toString()) }
            }) to buildJsonObject {
                put("summary", "Task")
                put("taskTypeId", buildJsonObject {})
            },
            schema(buildJsonObject {
                putJsonObject("workOps") { put("projectId", projectId.toString()) }
            }) to buildJsonObject {
                put("summary", "Task")
                put("dueDate", buildJsonObject {})
            },
        )

        malformedInputs.forEach { (form, attributes) ->
            assertFailsWith<IllegalArgumentException> {
                processor.process(profileId, submissionInput(attributes), form)
            }
        }
        coVerify(exactly = 0) { taskService.create(any(), any(), any(), any()) }
    }
}
