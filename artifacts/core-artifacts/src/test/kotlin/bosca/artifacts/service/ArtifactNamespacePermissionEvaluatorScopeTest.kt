package bosca.artifacts.service

import bosca.artifacts.model.ArtifactNamespace
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtifactNamespacePermissionEvaluatorScopeTest {
    private val service = mockk<ArtifactRepositoryService>()
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val evaluator = ArtifactNamespacePermissionEvaluator(service, securityService, GroupEvaluator(securityService))
    private val group = Group(id = UUID.random(), name = "publishers", description = "", type = GroupType.PRINCIPAL)
    private val namespace = mockk<ArtifactNamespace>(relaxed = true)

    private fun token(scope: String): AuthenticationContext = mockk {
        every { principal() } returns ScopedAuthenticatedPrincipal(
            Principal(id = UUID.random(), anonymous = false), listOf(group), listOf(scope), null, 1L,
        )
    }

    private fun grant(action: PermissionAction) = mockk<EntityPermission> {
        every { entityId } returns UUID.random()
        every { groupId } returns group.id
        every { this@mockk.action } returns action
    }

    @Test
    fun `broader artifact scopes cover narrower namespace actions`() = runTest {
        val actions = listOf(PermissionAction.VIEW, PermissionAction.LIST, PermissionAction.EDIT, PermissionAction.DELETE, PermissionAction.MANAGE)
        coEvery { service.getPermissions(namespace) } returns actions.map { grant(it) }
        coEvery { service.isParentAllowed(any(), any(), any()) } returns false

        val expected = mapOf(
            "artifacts:pull" to listOf(true, true, false, false, false),
            "artifacts:push" to listOf(true, true, true, false, false),
            "artifacts:admin" to listOf(true, true, true, true, true),
        )
        for ((scope, allowed) in expected) {
            assertEquals(allowed, actions.map { evaluator.isAllowed(token(scope), namespace, it) }, scope)
        }
    }
}
