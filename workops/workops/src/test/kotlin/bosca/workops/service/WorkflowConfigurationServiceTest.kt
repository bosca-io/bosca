package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.workflow.*
import bosca.workops.repository.WorkflowRepository
import bosca.workops.repository.WorkflowSchemeRepository
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkflowConfigurationServiceTest {
    private val workflows = mockk<WorkflowRepository>()
    private val schemes = mockk<WorkflowSchemeRepository>()
    private val service = WorkflowServiceImpl(workflows, schemes, mockk(), mockk())
    private val id = UUID.random()
    private val workflow = Workflow(id, "Stored", "Notes", UUID.random(), 7)
    private val scheme = WorkflowScheme(UUID.random(), "Scheme", defaultWorkflowId = id, version = 4)

    @Test fun `create starts an empty definition and update changes only its header at the requested revision`() = runTest {
        coEvery { workflows.addWorkflow("New", null, null) } returns Workflow(id, "New")
        assertNull(service.createWorkflow(CreateWorkflowInput("New")).initialStateId)
        val updated = workflow.copy(name = "Changed", description = null, version = 8)
        coEvery { workflows.updateWorkflow(id, "Changed", null, 7) } returns updated
        assertEquals(updated, service.updateWorkflow(id, UpdateWorkflowInput("Changed", expectedVersion = 7)))
        coVerify(exactly = 1) { workflows.updateWorkflow(id, "Changed", null, 7) }
    }

    @Test fun `all configuration writes reject blank names before repository access`() = runTest {
        for (name in listOf("", " \n\t ")) {
            assertFailsWith<WorkOpsValidationException> { service.createWorkflow(CreateWorkflowInput(name)) }
            assertFailsWith<WorkOpsValidationException> { service.updateWorkflow(id, UpdateWorkflowInput(name, expectedVersion = 7)) }
            assertFailsWith<WorkOpsValidationException> { service.createScheme(CreateWorkflowSchemeInput(name, defaultWorkflowId = id)) }
            assertFailsWith<WorkOpsValidationException> { service.updateScheme(scheme.id, UpdateWorkflowSchemeInput(name, defaultWorkflowId = id, expectedVersion = 4)) }
        }
        coVerify { workflows wasNot Called; schemes wasNot Called }
    }

    @Test fun `stale header writes fail without replacing an inspected version`() = runTest {
        coEvery { workflows.updateWorkflow(id, "Changed", null, 7) } returns null
        assertFailsWith<OptimisticLockFailedException> { service.updateWorkflow(id, UpdateWorkflowInput("Changed", expectedVersion = 7)) }
        coEvery { schemes.getById(scheme.id) } returns scheme.copy(version = 9)
        coEvery { workflows.getWorkflowById(id) } returns workflow
        coEvery { schemes.update(scheme.id, "Changed", null, id, scheme.perTaskTypeWorkflowIds, 4) } returns null
        assertFailsWith<OptimisticLockFailedException> { service.updateScheme(scheme.id, UpdateWorkflowSchemeInput("Changed", defaultWorkflowId = id, expectedVersion = 4)) }
    }

    @Test fun `scheme creation validates the default and starts with no overrides`() = runTest {
        coEvery { workflows.getWorkflowById(id) } returns workflow
        coEvery { schemes.add("New", "Description", id, JsonObject(emptyMap())) } returns scheme
        assertSame(scheme, service.createScheme(CreateWorkflowSchemeInput("New", "Description", id)))
        coEvery { workflows.getWorkflowById(id) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.createScheme(CreateWorkflowSchemeInput("New", defaultWorkflowId = id)) }
        coVerify(exactly = 1) { schemes.add(any(), any(), any(), any()) }
    }

    @Test fun `scheme header and default updates retain the entire stored override value`() = runTest {
        val selected = UUID.random()
        coEvery { workflows.getWorkflowById(selected) } returns workflow.copy(id = selected)
        for (overrides in listOf(
            JsonObject(mapOf(UUID.random().toString() to JsonPrimitive(id.toString()), "extra" to buildJsonObject { put("kept", true) })),
            JsonArray(listOf(JsonPrimitive("opaque"))),
        )) {
            val stored = scheme.copy(perTaskTypeWorkflowIds = overrides)
            coEvery { schemes.getById(scheme.id) } returns stored
            coEvery { schemes.update(scheme.id, "Changed", "New notes", selected, overrides, 4) } returns stored.copy(defaultWorkflowId = selected, version = 5)
            val result = service.updateScheme(scheme.id, UpdateWorkflowSchemeInput("Changed", "New notes", selected, 4))
            assertEquals(overrides, result.perTaskTypeWorkflowIds)
            assertEquals(selected, result.defaultWorkflowId)
        }
    }

    @Test fun `scheme update reports missing scheme or default before writing`() = runTest {
        coEvery { schemes.getById(scheme.id) } returns null
        val input = UpdateWorkflowSchemeInput("Changed", defaultWorkflowId = id, expectedVersion = 4)
        assertFailsWith<WorkOpsNotFoundException> { service.updateScheme(scheme.id, input) }
        coEvery { schemes.getById(scheme.id) } returns scheme
        coEvery { workflows.getWorkflowById(id) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.updateScheme(scheme.id, input) }
        coVerify(exactly = 0) { schemes.update(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `workflow deletion rejects default and per type scheme references including uppercase UUIDs`() = runTest {
        coEvery { workflows.getWorkflowById(id) } returns workflow
        val references = listOf(scheme, scheme.copy(defaultWorkflowId = UUID.random(), perTaskTypeWorkflowIds = buildJsonObject { put(UUID.random().toString(), id.toString().uppercase()) }))
        for (reference in references) {
            coEvery { schemes.listAll() } returns listOf(reference)
            assertFailsWith<WorkOpsValidationException> { service.deleteWorkflow(id) }
        }
        coVerify(exactly = 0) { workflows.deleteWorkflow(any()) }
    }

    @Test fun `unreferenced deletion tolerates unrelated opaque override values and missing definitions fail`() = runTest {
        coEvery { workflows.getWorkflowById(id) } returns workflow
        coEvery { schemes.listAll() } returns listOf(
            scheme.copy(defaultWorkflowId = UUID.random(), perTaskTypeWorkflowIds = JsonArray(emptyList())),
            scheme.copy(defaultWorkflowId = UUID.random(), perTaskTypeWorkflowIds = buildJsonObject { put("invalid", JsonObject(emptyMap())); put("other", "not-a-uuid") }),
        )
        coEvery { workflows.deleteWorkflow(id) } just Runs
        service.deleteWorkflow(id)
        coVerify(exactly = 1) { workflows.deleteWorkflow(id) }
        coEvery { workflows.getWorkflowById(id) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.deleteWorkflow(id) }
    }

    @Test fun `scheme deletion retains persistence failures and rejects missing identities`() = runTest {
        coEvery { schemes.getById(scheme.id) } returns scheme
        coEvery { schemes.deleteById(scheme.id) } throws IllegalStateException("Referenced by project")
        assertEquals("Referenced by project", assertFailsWith<IllegalStateException> { service.deleteScheme(scheme.id) }.message)
        coEvery { schemes.deleteById(scheme.id) } just Runs
        service.deleteScheme(scheme.id)
        coEvery { schemes.getById(scheme.id) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.deleteScheme(scheme.id) }
    }

    @Test fun `configuration cancellation propagates`() = runTest {
        coEvery { workflows.addWorkflow(any(), any(), any()) } throws CancellationException("Cancelled")
        assertFailsWith<CancellationException> { service.createWorkflow(CreateWorkflowInput("New")) }
    }
}
