@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.workops.pipeline

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUIDSerializer
import bosca.workops.model.project.Project
import bosca.workops.model.project.ProjectRepository
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.spec.Spec
import bosca.workops.model.sprint.Sprint
import bosca.workops.model.task.Task
import bosca.workops.service.ProjectRepositoryService
import bosca.workops.service.ProjectService
import bosca.workops.service.RequirementService
import bosca.workops.service.SpecService
import bosca.workops.service.SprintService
import bosca.workops.service.TaskService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class WorkOpsResolverNodesTest {

    private val taskService = mockk<TaskService>()
    private val specService = mockk<SpecService>()
    private val requirementService = mockk<RequirementService>()
    private val projectService = mockk<ProjectService>()
    private val projectRepositoryService = mockk<ProjectRepositoryService>()
    private val sprintService = mockk<SprintService>()

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<TaskService> { taskService }
        provides<SpecService> { specService }
        provides<RequirementService> { requirementService }
        provides<ProjectService> { projectService }
        provides<ProjectRepositoryService> { projectRepositoryService }
        provides<SprintService> { sprintService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("" to value))

    @Test
    fun `get task resolves the task for a uuid value`() = runTest {
        val taskId = Uuid.random()
        val task = mockk<Task>()
        coEvery { taskService.getById(taskId) } returns task

        val node = TaskEventToTaskNode(id = "n1")
        val out = node.run(context, inputs(PipelineValue.of(taskId, UUIDSerializer())))
        assertSame(task, out.result?.value)
    }

    @Test
    fun `get task resolves a bare uuid string`() = runTest {
        val taskId = Uuid.random()
        val task = mockk<Task>()
        coEvery { taskService.getById(taskId) } returns task

        val node = TaskEventToTaskNode(id = "n1")
        val out = node.run(context, inputs(PipelineValue.ofJson(JsonPrimitive(taskId.toString()))))
        assertSame(task, out.result?.value)
    }

    @Test
    fun `get spec resolves the spec for a uuid value`() = runTest {
        val specId = Uuid.random()
        val spec = mockk<Spec>()
        coEvery { specService.getById(specId) } returns spec

        val node = SpecEventToSpecNode(id = "n1")
        val out = node.run(context, inputs(PipelineValue.of(specId, UUIDSerializer())))
        assertSame(spec, out.result?.value)
    }

    @Test
    fun `get requirement resolves the requirement for a uuid value`() = runTest {
        val requirementId = Uuid.random()
        val requirement = mockk<Requirement>()
        coEvery { requirementService.getById(requirementId) } returns requirement

        val node = RequirementEventToRequirementNode(id = "n1")
        val out = node.run(context, inputs(PipelineValue.of(requirementId, UUIDSerializer())))
        assertSame(requirement, out.result?.value)
    }

    @Test
    fun `get task fails clearly when the task does not exist`() = runTest {
        val taskId = Uuid.random()
        coEvery { taskService.getById(taskId) } returns null

        val node = TaskEventToTaskNode(id = "n1", name = "Resolve task")
        val e = assertFailsWith<IllegalStateException> {
            node.run(context, inputs(PipelineValue.of(taskId, UUIDSerializer())))
        }
        assertSame(true, e.message?.contains("Resolve task"))
        val unnamedFailure = assertFailsWith<IllegalStateException> {
            TaskEventToTaskNode(id = "task-by-id").run(
                context,
                inputs(PipelineValue.of(taskId, UUIDSerializer())),
            )
        }
        assertTrue(unnamedFailure.message.orEmpty().contains("task-by-id"))
    }

    @Test
    fun `spec and requirement resolver failures use authored names or ids`() = runTest {
        val specId = Uuid.random()
        val requirementId = Uuid.random()
        coEvery { specService.getById(specId) } returns null
        coEvery { requirementService.getById(requirementId) } returns null

        listOf(
            SpecEventToSpecNode(id = "spec-by-id") to "spec-by-id",
            SpecEventToSpecNode(id = "spec-by-id", name = "Resolve spec") to "Resolve spec",
        ).forEach { (node, expectedLabel) ->
            val failure = assertFailsWith<IllegalStateException> {
                node.run(context, inputs(PipelineValue.of(specId, UUIDSerializer())))
            }
            assertTrue(failure.message.orEmpty().contains(expectedLabel))
        }
        listOf(
            RequirementEventToRequirementNode(id = "requirement-by-id") to "requirement-by-id",
            RequirementEventToRequirementNode(id = "requirement-by-id", name = "Resolve requirement") to "Resolve requirement",
        ).forEach { (node, expectedLabel) ->
            val failure = assertFailsWith<IllegalStateException> {
                node.run(context, inputs(PipelineValue.of(requirementId, UUIDSerializer())))
            }
            assertTrue(failure.message.orEmpty().contains(expectedLabel))
        }
    }

    @Test
    fun `get project returns the project and reports a missing project by node name`() = runTest {
        val projectId = Uuid.random()
        val project = mockk<Project>()
        coEvery { projectService.getById(projectId) } returnsMany listOf(project, null)
        val node = GetProjectNode(id = "project", name = "Resolve project")

        val resolved = node.run(context, inputs(PipelineValue.of(projectId, UUIDSerializer())))

        assertSame(project, resolved.result?.value)
        val failure = assertFailsWith<IllegalStateException> {
            node.run(context, inputs(PipelineValue.of(projectId, UUIDSerializer())))
        }
        assertTrue(failure.message.orEmpty().contains("Resolve project"))
        assertTrue(failure.message.orEmpty().contains(projectId.toString()))
    }

    @Test
    fun `get sprint returns the sprint and reports a missing sprint by node id`() = runTest {
        val sprintId = Uuid.random()
        val sprint = mockk<Sprint>()
        coEvery { sprintService.getById(sprintId) } returnsMany listOf(sprint, null)
        val node = GetSprintNode(id = "resolve-sprint")

        val resolved = node.run(context, inputs(PipelineValue.of(sprintId, UUIDSerializer())))

        assertSame(sprint, resolved.result?.value)
        val failure = assertFailsWith<IllegalStateException> {
            node.run(context, inputs(PipelineValue.of(sprintId, UUIDSerializer())))
        }
        assertTrue(failure.message.orEmpty().contains("resolve-sprint"))
        assertTrue(failure.message.orEmpty().contains(sprintId.toString()))
    }

    @Test
    fun `project repositories resolve bare project and release-project identifiers`() = runTest {
        val bareId = Uuid.random()
        val projectObjectId = Uuid.random()
        val releaseProjectId = Uuid.random()
        val bareRepositories = listOf(mockk<ProjectRepository>())
        val projectRepositories = listOf(mockk<ProjectRepository>(), mockk<ProjectRepository>())
        val releaseRepositories = emptyList<ProjectRepository>()
        coEvery { projectRepositoryService.list(bareId) } returns bareRepositories
        coEvery { projectRepositoryService.list(projectObjectId) } returns projectRepositories
        coEvery { projectRepositoryService.list(releaseProjectId) } returns releaseRepositories
        val node = GetProjectRepositoriesNode(id = "repositories")

        val bare = node.run(context, inputs(PipelineValue.of(bareId, UUIDSerializer())))
        val projectObject = node.run(
            context,
            inputs(PipelineValue.ofJson(buildJsonObject { put("id", projectObjectId.toString()) })),
        )
        val releaseObject = node.run(
            context,
            inputs(PipelineValue.ofJson(buildJsonObject { put("projectId", releaseProjectId.toString()) })),
        )

        assertEquals(bareRepositories, bare.result?.value)
        assertEquals(projectRepositories, projectObject.result?.value)
        assertEquals(releaseRepositories, releaseObject.result?.value)
    }

    @Test
    fun `project repositories reject absent blank and non-primitive object identifiers`() = runTest {
        val node = GetProjectRepositoriesNode(id = "repositories", name = "List project repositories")
        val invalid = listOf(
            JsonObject(emptyMap()),
            buildJsonObject { put("id", " ") },
            buildJsonObject { put("projectId", buildJsonObject { put("nested", true) }) },
            buildJsonObject { put("projectId", JsonNull) },
        )

        invalid.forEach { value ->
            val failure = assertFailsWith<IllegalStateException> {
                node.run(context, inputs(PipelineValue.ofJson(value)))
            }
            assertTrue(failure.message.orEmpty().contains("List project repositories"))
            assertTrue(failure.message.orEmpty().contains("requires a project id"))
        }
    }

    @Test
    fun `resolver node serialization preserves authored fields and applies defaults`() {
        val minimal = json.decodeFromString(GetProjectNode.serializer(), """{"id":"project"}""")
        assertEquals("", minimal.name)
        assertEquals("", minimal.description)
        assertEquals(NodePosition(), minimal.position)

        val authored = GetSprintNode(
            id = "sprint",
            name = "Resolve sprint",
            description = "Load its details",
            position = NodePosition(12.0, 24.0),
        )
        val restored = json.decodeFromString(GetSprintNode.serializer(), json.encodeToString(GetSprintNode.serializer(), authored))
        assertEquals(authored.id, restored.id)
        assertEquals(authored.name, restored.name)
        assertEquals(authored.description, restored.description)
        assertEquals(authored.position, restored.position)

        val repositories = GetProjectRepositoriesNode(
            id = "repositories",
            name = "Repositories",
            description = "List repositories",
            position = NodePosition(3.0, 4.0),
        )
        val repositoriesJson = json.encodeToString(GetProjectRepositoriesNode.serializer(), repositories)
        assertTrue(repositoriesJson.contains("Repositories"))
        assertTrue(repositoriesJson.contains("List repositories"))
    }
}
