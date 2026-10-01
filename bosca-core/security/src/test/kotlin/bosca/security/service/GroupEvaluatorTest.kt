package bosca.security.service

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.serialization.UUID
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupEvaluatorTest {

    private val securityService = mockk<SecurityService>()
    private val evaluator = GroupEvaluator(securityService)

    private fun createAuthContext(groups: List<Group>): AuthenticationContext {
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = AuthenticatedPrincipal(principal, groups)
        val callAuthContext = mockk<bosca.server.auth.CallAuthenticationContext>(relaxed = true)
        every { callAuthContext.principal(any()) } returns authenticatedPrincipal
        val providers = AuthenticationProviders(arrayOf("default"))
        return AuthenticationContext(callAuthContext, providers)
    }

    private fun adminGroup() = Group(UUID.random(), "administrators", "Admins", GroupType.SYSTEM)
    private fun editorGroup() = Group(UUID.random(), "editors", "Editors", GroupType.SYSTEM)
    private fun managerGroup() = Group(UUID.random(), "managers", "Managers", GroupType.SYSTEM)
    private fun saGroup() = Group(UUID.random(), "sa", "Service Accounts", GroupType.SYSTEM)
    private fun userGroup() = Group(UUID.random(), "users", "Users", GroupType.SYSTEM)

    // hasAdminGroup tests

    @Test
    fun `hasAdminGroup returns true for admin`() {
        assertTrue(evaluator.hasAdminGroup(createAuthContext(listOf(adminGroup()))))
    }

    @Test
    fun `hasAdminGroup returns false for non-admin`() {
        assertFalse(evaluator.hasAdminGroup(createAuthContext(listOf(editorGroup()))))
    }

    @Test
    fun `hasAdminGroup returns false for null authentication`() {
        assertFalse(evaluator.hasAdminGroup(null))
    }

    @Test
    fun `hasAdminGroup returns false for no groups`() {
        assertFalse(evaluator.hasAdminGroup(createAuthContext(emptyList())))
    }

    @Test
    fun `verifyHasAdminGroup throws for non-admin`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasAdminGroup(createAuthContext(listOf(editorGroup())))
        }
    }

    @Test
    fun `verifyHasAdminGroup throws for null`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasAdminGroup(null)
        }
    }

    @Test
    fun `verifyHasAdminGroup passes for admin`() {
        evaluator.verifyHasAdminGroup(createAuthContext(listOf(adminGroup())))
    }

    // hasEditorGroup tests

    @Test
    fun `hasEditorGroup returns true for editor`() {
        assertTrue(evaluator.hasEditorGroup(createAuthContext(listOf(editorGroup()))))
    }

    @Test
    fun `hasEditorGroup returns true for admin`() {
        assertTrue(evaluator.hasEditorGroup(createAuthContext(listOf(adminGroup()))))
    }

    @Test
    fun `hasEditorGroup returns true for sa`() {
        assertTrue(evaluator.hasEditorGroup(createAuthContext(listOf(saGroup()))))
    }

    @Test
    fun `hasEditorGroup returns true for manager`() {
        assertTrue(evaluator.hasEditorGroup(createAuthContext(listOf(managerGroup()))))
    }

    @Test
    fun `hasEditorGroup returns false for regular user`() {
        assertFalse(evaluator.hasEditorGroup(createAuthContext(listOf(userGroup()))))
    }

    @Test
    fun `hasEditorGroup returns false for null`() {
        assertFalse(evaluator.hasEditorGroup(null))
    }

    @Test
    fun `verifyHasEditorGroup throws for regular user`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasEditorGroup(createAuthContext(listOf(userGroup())))
        }
    }

    @Test
    fun `verifyHasEditorGroup passes for editor`() {
        evaluator.verifyHasEditorGroup(createAuthContext(listOf(editorGroup())))
    }

    // hasManagerGroup tests

    @Test
    fun `hasManagerGroup returns true for manager`() {
        assertTrue(evaluator.hasManagerGroup(createAuthContext(listOf(managerGroup()))))
    }

    @Test
    fun `hasManagerGroup returns true for admin`() {
        assertTrue(evaluator.hasManagerGroup(createAuthContext(listOf(adminGroup()))))
    }

    @Test
    fun `hasManagerGroup returns false for editor`() {
        assertFalse(evaluator.hasManagerGroup(createAuthContext(listOf(editorGroup()))))
    }

    @Test
    fun `hasManagerGroup returns false for null`() {
        assertFalse(evaluator.hasManagerGroup(null))
    }

    @Test
    fun `verifyHasManagerGroup throws for editor`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasManagerGroup(createAuthContext(listOf(editorGroup())))
        }
    }

    @Test
    fun `verifyHasManagerGroup passes for manager`() {
        evaluator.verifyHasManagerGroup(createAuthContext(listOf(managerGroup())))
    }

    // hasSaGroup tests

    @Test
    fun `hasSaGroup returns true for sa`() {
        assertTrue(evaluator.hasSaGroup(createAuthContext(listOf(saGroup()))))
    }

    @Test
    fun `hasSaGroup returns true for admin`() {
        assertTrue(evaluator.hasSaGroup(createAuthContext(listOf(adminGroup()))))
    }

    @Test
    fun `hasSaGroup returns false for editor`() {
        assertFalse(evaluator.hasSaGroup(createAuthContext(listOf(editorGroup()))))
    }

    @Test
    fun `hasSaGroup returns false for null`() {
        assertFalse(evaluator.hasSaGroup(null))
    }

    @Test
    fun `verifyHasSaGroup throws for editor`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasSaGroup(createAuthContext(listOf(editorGroup())))
        }
    }

    @Test
    fun `verifyHasSaGroup passes for sa`() {
        evaluator.verifyHasSaGroup(createAuthContext(listOf(saGroup())))
    }

    // hasGroup tests

    @Test
    fun `hasGroup returns true when principal has the group`() {
        assertTrue(evaluator.hasGroup(createAuthContext(listOf(userGroup())), "users"))
    }

    @Test
    fun `hasGroup returns true for admin even without specific group`() {
        assertTrue(evaluator.hasGroup(createAuthContext(listOf(adminGroup())), "some-group"))
    }

    @Test
    fun `hasGroup returns false when principal lacks the group`() {
        assertFalse(evaluator.hasGroup(createAuthContext(listOf(userGroup())), "some-other-group"))
    }

    @Test
    fun `hasGroup returns false for null authentication`() {
        assertFalse(evaluator.hasGroup(null, "users"))
    }

    // Edge: authentication with no principal

    @Test
    fun `hasAdminGroup returns false when principal returns null`() {
        val callAuthContext = mockk<bosca.server.auth.CallAuthenticationContext>(relaxed = true)
        every { callAuthContext.principal(any()) } returns null
        val providers = AuthenticationProviders(arrayOf("default"))
        val authContext = AuthenticationContext(callAuthContext, providers)

        assertFalse(evaluator.hasAdminGroup(authContext))
    }
}
