package bosca.chat.security

import bosca.chat.model.ChatChannel
import bosca.chat.service.ChatService
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

class ChatChannelPermissionEvaluatorScopeTest {
    private val service = mockk<ChatService>()
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val evaluator = ChatChannelPermissionEvaluator(service, securityService, GroupEvaluator(securityService))
    private val group = Group(id = UUID.random(), name = "users", description = "", type = GroupType.PRINCIPAL)
    private val entity = mockk<ChatChannel>(relaxed = true)

    private fun token(scopes: List<String>): AuthenticationContext = mockk {
        every { principal() } returns ScopedAuthenticatedPrincipal(
            Principal(id = UUID.random(), anonymous = false), listOf(group), scopes, null, 1L,
        )
    }

    @Test
    fun `messages edit token can use an explicit EXECUTE grant to post in a channel`() = runTest {
        val grant = mockk<EntityPermission> {
            every { entityId } returns UUID.random()
            every { groupId } returns group.id
            every { action } returns PermissionAction.EXECUTE
        }
        coEvery { service.getPermissions(entity) } returns listOf(grant)
        coEvery { service.isParentAllowed(any(), any(), any()) } returns false

        assertTrue(evaluator.isAllowed(token(listOf("messages:edit")), entity, PermissionAction.EXECUTE))
        assertFalse(evaluator.isAllowed(token(listOf("messages:read")), entity, PermissionAction.EXECUTE))
    }
}
