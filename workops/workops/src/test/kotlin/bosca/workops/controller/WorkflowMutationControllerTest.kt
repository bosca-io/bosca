package bosca.workops.controller

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.*
import bosca.serialization.UUID
import bosca.workops.model.workflow.*
import bosca.workops.service.WorkflowService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WorkflowMutationControllerTest {
    private val service = mockk<WorkflowService>(relaxed = true)
    private val controller = WorkflowMutationController(service, GroupEvaluator(mockk<SecurityService>()))
    private val principal = Principal(UUID.random())
    private val adminGroup = Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
    private val admin = ImpersonatedAuthenticationContext(principal, listOf(adminGroup))
    private val id = UUID.random()
    private val schemeId = UUID.random()
    private val create = CreateWorkflowInput("Workflow", "Description")
    private val update = UpdateWorkflowInput("Changed", "New description", 7)
    private val createScheme = CreateWorkflowSchemeInput("Scheme", "Notes", id)
    private val updateScheme = UpdateWorkflowSchemeInput("Changed scheme", null, id, 4)

    @Test fun `administrator CRUD delegates all fields versions and identities`() = runTest {
        val workflow = Workflow(id, "Workflow")
        val scheme = WorkflowScheme(schemeId, "Scheme", defaultWorkflowId = id)
        coEvery { service.createWorkflow(create) } returns workflow
        coEvery { service.updateWorkflow(id, update) } returns workflow
        coEvery { service.createScheme(createScheme) } returns scheme
        coEvery { service.updateScheme(schemeId, updateScheme) } returns scheme
        assertSame(workflow, controller.create(admin, create))
        assertSame(workflow, controller.update(admin, id, update))
        assertTrue(controller.delete(admin, id))
        assertSame(scheme, controller.createScheme(admin, createScheme))
        assertSame(scheme, controller.updateScheme(admin, schemeId, updateScheme))
        assertTrue(controller.deleteScheme(admin, schemeId))
        coVerify(exactly = 1) { service.deleteWorkflow(id); service.deleteScheme(schemeId) }
    }

    @Test fun `ordinary anonymous and restricted administrator tokens cannot reach any mutation`() = runTest {
        val restricted = mockk<AuthenticationContext> {
            every { principal() } returns ScopedAuthenticatedPrincipal(principal, listOf(adminGroup), listOf("content:view"), null, 1)
        }
        val anonymous = mockk<AuthenticationContext> { every { principal() } returns null }
        for (auth in listOf(ImpersonatedAuthenticationContext(principal, emptyList()), restricted, anonymous)) {
            assertFails { controller.create(auth, create) }
            assertFails { controller.update(auth, id, update) }
            assertFails { controller.delete(auth, id) }
            assertFails { controller.createScheme(auth, createScheme) }
            assertFails { controller.updateScheme(auth, schemeId, updateScheme) }
            assertFails { controller.deleteScheme(auth, schemeId) }
        }
        coVerify { service wasNot Called }
    }
}
