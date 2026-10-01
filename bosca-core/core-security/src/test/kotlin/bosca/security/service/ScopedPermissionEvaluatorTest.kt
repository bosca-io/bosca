package bosca.security.service

import bosca.graphql.Batch
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.security.model.Principal
import bosca.security.service.PermissionEvaluatorTest.TestEntity
import bosca.security.service.PermissionEvaluatorTest.TestPermission
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ScopedPermissionEvaluatorTest {
    private val security = mockk<SecurityService>()
    private val groups = GroupEvaluator(security)
    private val service = mockk<PermissionService<TestEntity, UUID>>()
    private val team = Group(id = UUID.random(), name = "team", description = "", type = GroupType.PRINCIPAL)
    private val entity = TestEntity()
    private val evaluator = object : PermissionEvaluator<TestEntity, UUID>() {
        override val service = this@ScopedPermissionEvaluatorTest.service
        override val securityService = security
        override val groupEvaluator = groups
    }

    private fun authentication(scopes: List<String>?, group: Group = team): AuthenticationContext =
        mockk<AuthenticationContext> {
            every { principal() } returns ScopedAuthenticatedPrincipal(
                Principal(id = UUID.random(), anonymous = false), listOf(group), scopes, null, 1L,
            )
        }

    private fun grant(action: PermissionAction) {
        val permissions = listOf<EntityPermission>(TestPermission(entity.id, team.id, action))
        coEvery { service.getPermissions(any()) } returns permissions
        coEvery { service.addPermissionsToBatch(any()) } coAnswers {
            firstArg<Batch<UUID, List<EntityPermission>>>().setData(entity.id, permissions)
        }
        coEvery { service.isParentAllowed(any(), any(), any()) } returns false
    }

    @Test
    fun `read only token cannot use explicit write grants through any evaluator entry point`() = runTest {
        val authentication = authentication(listOf("content:view"))
        for (action in listOf(PermissionAction.EDIT, PermissionAction.DELETE, PermissionAction.MANAGE, PermissionAction.EXECUTE, PermissionAction.IMPERSONATE)) {
            grant(action)
            assertFalse(evaluator.isAllowed(authentication, entity, action))
            assertFalse(evaluator.isContentAllowed(authentication, entity, action))
            assertFalse(evaluator.isSupplementaryAllowed(authentication, entity, action))
            assertEquals(listOf(false), evaluator.isAllowed(authentication, listOf(entity), action))
            assertEquals(listOf(false), evaluator.isContentAllowed(authentication, listOf(entity), action))
            assertEquals(listOf(false), evaluator.isSupplementaryAllowed(authentication, listOf(entity), action))
            assertEquals(listOf(false), evaluator.isAllowed(listOf(authentication), entity, action))
            assertEquals(emptyList(), evaluator.filterAllowed(authentication, listOf(entity), action))
        }
    }

    @Test
    fun `matching scope and group permission permit access`() = runTest {
        for ((action, scope) in listOf(
            PermissionAction.VIEW to "content:view", PermissionAction.LIST to "content:view",
            PermissionAction.EDIT to "content:edit", PermissionAction.DELETE to "content:delete",
            PermissionAction.MANAGE to "content:manage", PermissionAction.EXECUTE to "jobs:execute",
            PermissionAction.IMPERSONATE to "security:manage",
        )) {
            grant(action)
            assertTrue(evaluator.isAllowed(authentication(listOf(scope)), entity, action))
        }
        grant(PermissionAction.MANAGE)
        assertTrue(evaluator.isAllowed(authentication(listOf("content:edit")), entity, PermissionAction.EDIT))
    }

    @Test
    fun `inherited permission cannot bypass token scope`() = runTest {
        coEvery { service.getPermissions(any()) } returns emptyList()
        coEvery { service.isParentAllowed(any(), any(), any()) } returns true
        assertFalse(evaluator.isAllowed(authentication(listOf("content:view")), entity, PermissionAction.EDIT))
        coVerify(exactly = 0) { service.isParentAllowed(any(), any(), any()) }
        assertTrue(evaluator.isAllowed(authentication(listOf("content:edit")), entity, PermissionAction.EDIT))
    }

    @Test
    fun `administrator and editor roles cannot bypass narrow scopes`() = runTest {
        coEvery { service.getPermissions(any()) } returns emptyList()
        for (role in listOf("administrators", "sa", "editors", "managers")) {
            val group = team.copy(name = role)
            assertFalse(evaluator.isAllowed(authentication(listOf("content:view"), group), entity, PermissionAction.EDIT))
            assertTrue(evaluator.isAllowed(authentication(listOf("content:edit"), group), entity, PermissionAction.EDIT))
        }
        assertTrue(evaluator.isAllowed(authentication(listOf("security:manage"), team.copy(name = "administrators")), entity, PermissionAction.MANAGE))
    }

    @Test
    fun `public reads remain available with unrelated or empty scopes`() = runTest {
        coEvery { service.getPermissions(any()) } returns emptyList()
        val authentication = authentication(emptyList())
        assertTrue(evaluator.isAllowed(authentication, entity.copy(public = true), PermissionAction.VIEW))
        assertTrue(evaluator.isAllowed(authentication, entity.copy(publicList = true), PermissionAction.LIST))
        assertTrue(evaluator.isContentAllowed(authentication, entity.copy(publicContent = true), PermissionAction.VIEW))
        assertTrue(evaluator.isSupplementaryAllowed(authentication, entity.copy(publicSupplementary = true), PermissionAction.VIEW))
    }

    @Test
    fun `unrestricted tokens and interactive principals retain entity permissions`() = runTest {
        grant(PermissionAction.EDIT)
        assertTrue(evaluator.isAllowed(authentication(null), entity, PermissionAction.EDIT))
        val interactive = mockk<AuthenticationContext> {
            every { principal() } returns AuthenticatedPrincipal(Principal(id = UUID.random()), listOf(team))
        }
        assertTrue(evaluator.isAllowed(interactive, entity, PermissionAction.EDIT))
    }

    @Test
    fun `domain scope mapping also applies to role based access`() = runTest {
        coEvery { service.getPermissions(any()) } returns emptyList()
        val domainEvaluator = object : PermissionEvaluator<TestEntity, UUID>() {
            override val service = this@ScopedPermissionEvaluatorTest.service
            override val securityService = security
            override val groupEvaluator = groups
            override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction) =
                groups.hasScope(authentication, "profiles:edit")
        }
        val editor = team.copy(name = "editors")
        assertFalse(domainEvaluator.isAllowed(authentication(listOf("content:edit"), editor), entity, PermissionAction.EDIT))
        assertTrue(domainEvaluator.isAllowed(authentication(listOf("profiles:edit"), editor), entity, PermissionAction.EDIT))
    }
}
