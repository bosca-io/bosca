package bosca.content.security

import bosca.content.collection.model.ICollection
import bosca.content.collection.service.CollectionService
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionPermissionEvaluatorScopeTest {
    private val service = mockk<CollectionService>()
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val evaluator = CollectionPermissionEvaluator(service, securityService, GroupEvaluator(securityService))
    private val group = Group(id = UUID.random(), name = "workflow", description = "", type = GroupType.PRINCIPAL)
    private val entity = mockk<ICollection>(relaxed = true)

    private fun token(scopes: List<String>): AuthenticationContext = mockk {
        every { principal() } returns ScopedAuthenticatedPrincipal(
            Principal(id = UUID.random(), anonymous = false), listOf(group), scopes, null, 1L,
        )
    }

    @Test
    fun `jobs execute token can use an explicit EXECUTE grant for collection transitions`() = runTest {
        val grant = mockk<EntityPermission> {
            every { entityId } returns UUID.random()
            every { groupId } returns group.id
            every { action } returns PermissionAction.EXECUTE
        }
        coEvery { service.getPermissions(entity) } returns listOf(grant)
        coEvery { service.isParentAllowed(any(), any(), any()) } returns false

        assertTrue(evaluator.isAllowed(token(listOf("jobs:execute")), entity, PermissionAction.EXECUTE))
        assertFalse(evaluator.isAllowed(token(listOf("collections:edit", "collections:manage")), entity, PermissionAction.EXECUTE))
    }
}
