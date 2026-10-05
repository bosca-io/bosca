package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.fields.TaskFieldConfiguration
import bosca.workops.model.project.Project
import bosca.workops.model.task.Task
import bosca.workops.repository.TaskFieldConfigurationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskCustomFieldServiceTest {

    private val repository = mockk<TaskFieldConfigurationRepository>()
    private val service = TaskCustomFieldServiceImpl(repository)
    private val schemeId = UUID.random()
    private val taskTypeId = UUID.random()

    private fun project(scheme: UUID? = schemeId) = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "OPS",
        name = "Operations",
        ownerProfileId = UUID.random(),
        defaultFieldConfigurationSchemeId = scheme,
    )

    private fun configuration(
        key: String,
        typeId: UUID? = null,
        required: Boolean = false,
        hidden: Boolean = false,
        default: JsonPrimitive? = null,
    ) = TaskFieldConfiguration(
        schemeId = schemeId,
        taskTypeId = typeId,
        fieldKey = key,
        required = required,
        hidden = hidden,
        defaultValueExpression = default,
    )

    private fun task(values: JsonObject) = Task(
        id = UUID.random(),
        key = "OPS-1",
        projectId = UUID.random(),
        taskTypeId = taskTypeId,
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Exercise custom fields",
        reporterProfileId = UUID.random(),
        customFieldValues = values,
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )

    @Test
    fun `configuration composes scheme defaults and task type overrides`() = runTest {
        assertEquals(emptyMap(), service.configFor(project(scheme = null), taskTypeId))
        coVerify(exactly = 0) { repository.listConfigurationsForTaskType(any(), any()) }

        val default = configuration("severity", default = JsonPrimitive("normal"))
        val override = configuration("severity", typeId = taskTypeId, default = JsonPrimitive("critical"))
        val otherType = configuration("ignored", typeId = UUID.random())
        coEvery { repository.listConfigurationsForTaskType(schemeId, taskTypeId) } returns
            listOf(override, otherType, default)

        assertEquals(override, service.configFor(project(), taskTypeId).getValue("severity"))
        assertEquals(setOf("severity"), service.configFor(project(), taskTypeId).keys)
    }

    @Test
    fun `create composition applies defaults preserves supplied values and enforces required fields`() = runTest {
        coEvery { repository.listConfigurationsForTaskType(schemeId, taskTypeId) } returns listOf(
            configuration("required", required = true),
            configuration("defaulted", default = JsonPrimitive("fallback")),
            configuration("optional"),
        )
        val supplied = JsonPrimitive("supplied")
        val unknown = JsonPrimitive(7)

        val result = service.composeForCreate(
            project(),
            taskTypeId,
            mapOf("required" to supplied, "unknown" to unknown),
        )

        assertEquals(supplied, result["required"])
        assertEquals(JsonPrimitive("fallback"), result["defaulted"])
        assertEquals(null, result["optional"])
        assertEquals(unknown, result["unknown"])
        assertFailsWith<WorkOpsValidationException> {
            service.composeForCreate(project(), taskTypeId, emptyMap())
        }
    }

    @Test
    fun `read filtering preserves manager data and hides only configured hidden fields`() = runTest {
        val values = JsonObject(
            mapOf(
                "hidden" to JsonPrimitive("secret"),
                "visible" to JsonPrimitive("shared"),
                "unconfigured" to JsonPrimitive("also shared"),
            ),
        )
        val task = task(values)
        coEvery { repository.listConfigurationsForTaskType(schemeId, taskTypeId) } returns listOf(
            configuration("hidden", hidden = true),
            configuration("visible", hidden = false),
        )

        assertEquals(values, service.filterForRead(project(), task, manager = true))
        assertEquals(
            JsonObject(values.filterKeys { it != "hidden" }),
            service.filterForRead(project(), task, manager = false),
        )
    }
}
