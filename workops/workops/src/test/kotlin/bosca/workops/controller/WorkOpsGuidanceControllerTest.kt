package bosca.workops.controller

import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.guidance.*
import bosca.workops.service.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WorkOpsGuidanceControllerTest {
    private val guidance = mockk<WorkOpsGuidanceService>(relaxed = true)
    private val projects = mockk<ProjectService>(relaxed = true)
    private val permissions = mockk<ProjectPermissionEvaluator>(relaxed = true)
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val id = UUID.random()
    private val auth = ImpersonatedAuthenticationContext(Principal(UUID.random()), emptyList())
    private val anonymous = mockk<AuthenticationContext> { every { principal() } returns null }
    private val queries = WorkOpsGuidanceQueryController(guidance, projects, permissions)
    private val mutations = WorkOpsGuidanceMutationController(guidance, projects, permissions, groups)
    private val input = WorkOpsGuidanceInput()

    @Test fun `effective and export require authentication and project view access`() = runTest {
        queries.effective(auth, null)
        queries.effective(auth, id)
        queries.exportSkill(auth, null, WorkOpsGuidancePhase.INVESTIGATION)
        queries.exportSkill(auth, id, WorkOpsGuidancePhase.IMPLEMENTATION)
        coVerify(exactly = 2) { permissions.verifyAllowed(auth, any(), PermissionAction.VIEW) }
        assertFailsWith<IllegalArgumentException> { queries.effective(anonymous, null) }
        assertFailsWith<IllegalArgumentException> { queries.exportSkill(anonymous, null, WorkOpsGuidancePhase.INVESTIGATION) }
        coEvery { permissions.verifyAllowed(any(), any(), any()) } throws IllegalStateException("Denied")
        assertFailsWith<IllegalStateException> { queries.effective(auth, id) }
        assertFailsWith<IllegalStateException> { queries.exportSkill(auth, id, WorkOpsGuidancePhase.INVESTIGATION) }
        coVerify(exactly = 2) { guidance.effective(any()) }
        coVerify(exactly = 2) { guidance.exportSkill(any(), any()) }
        coEvery { projects.getById(id) } returns null
        assertFailsWith<IllegalStateException> { queries.effective(auth, id) }
    }

    @Test fun `mutations require platform admin or project management before changing settings`() = runTest {
        mutations.save(auth, null, input, "revision")
        mutations.reset(auth, null, "revision")
        mutations.save(auth, id, input, "revision")
        mutations.reset(auth, id, "revision")
        verify(exactly = 2) { groups.verifyHasAdminGroup(auth) }
        coVerify(exactly = 2) { permissions.verifyAllowed(auth, any(), PermissionAction.MANAGE) }
        assertFailsWith<IllegalArgumentException> { mutations.save(anonymous, null, input, "revision") }
        assertFailsWith<IllegalArgumentException> { mutations.reset(anonymous, id, "revision") }
        every { groups.verifyHasAdminGroup(auth) } throws IllegalStateException("Denied")
        assertFailsWith<IllegalStateException> { mutations.save(auth, null, input, "revision") }
        assertFailsWith<IllegalStateException> { mutations.reset(auth, null, "revision") }
        coEvery { permissions.verifyAllowed(any(), any(), any()) } throws IllegalStateException("Denied")
        assertFailsWith<IllegalStateException> { mutations.save(auth, id, input, "revision") }
        assertFailsWith<IllegalStateException> { mutations.reset(auth, id, "revision") }
        coVerify(exactly = 2) { guidance.save(any(), any(), any()) }
        coVerify(exactly = 2) { guidance.reset(any(), any()) }
        coEvery { projects.getById(id) } returns null
        assertFailsWith<IllegalStateException> { mutations.reset(auth, id, "revision") }
    }

    @Test fun `import previews only a guide and all fields are exposed through explicit controllers`() {
        val phase = WorkOpsGuidancePhase.INTERFACES
        val guide = WorkOpsGuideInput(phase, "interfaces", "Use for contracts", "# Contracts")
        every { guidance.importSkill(phase, "skill") } returns guide
        val result = queries.importSkill(auth, phase, "skill")
        assertEquals(WorkOpsGuide(phase, "interfaces", "Use for contracts", "# Contracts", false), result)
        assertFailsWith<IllegalArgumentException> { queries.importSkill(anonymous, phase, "skill") }
        val policy = WorkOpsGuidancePolicy()
        val value = WorkOpsGuidance("revision", policy, true, listOf(result))
        WorkOpsGuideFieldController().apply {
            assertEquals(phase, phase(result)); assertEquals("interfaces", name(result)); assertEquals("Use for contracts", description(result))
            assertEquals("# Contracts", markdown(result)); assertFalse(inherited(result)); assertNull(metadataId(result)); assertNull(documentVersion(result)); assertNull(resourceId(result))
        }
        WorkOpsGuidanceFieldController().apply {
            assertEquals("revision", revision(value)); assertSame(policy, policy(value)); assertTrue(policyInherited(value)); assertEquals(listOf(result), guides(value))
        }
        WorkOpsGuidancePolicyFieldController().apply {
            assertTrue(requireInterfaceReview(policy)); assertTrue(requireTdd(policy)); assertTrue(requireTestQuality(policy))
            assertEquals(99.0, minimumLineCoverage(policy)); assertEquals(97.0, minimumBranchCoverage(policy))
        }
        assertSame(WorkOpsGuidanceQuery, WorkOpsQueryController().guidance())
        assertSame(WorkOpsGuidanceMutation, WorkOpsMutationController().guidance())
    }
}
