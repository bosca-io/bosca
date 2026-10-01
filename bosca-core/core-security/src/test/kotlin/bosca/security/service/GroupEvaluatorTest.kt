package bosca.security.service

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.serialization.UUID
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GroupEvaluatorTest {

    private val securityService = mockk<SecurityService>()
    private val evaluator = GroupEvaluator(securityService)

    private fun authContext(vararg groupNames: String): AuthenticationContext {
        val principal = Principal(id = UUID.random())
        val groups = groupNames.map { Group(id = UUID.random(), name = it, description = "", type = GroupType.SYSTEM) }
        val authenticatedPrincipal = AuthenticatedPrincipal(principal, groups)
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    private fun scopedAuthContext(vararg scopes: String): AuthenticationContext {
        val principal = ScopedAuthenticatedPrincipal(
            principal = Principal(id = UUID.random()),
            allGroups = emptyList(),
            scopes = scopes.toList(),
            allowedGroupIds = null,
            credentialId = 1L,
        )
        return object : AuthenticationContext(null, null) {
            override fun principal() = principal
        }
    }

    // --- hasScope ---

    @Test
    fun `hasScope allows authenticated non-token principals`() {
        assertTrue(evaluator.hasScope(authContext("users"), ApiTokenScopes.CONTENT_VIEW.name))
    }

    @Test
    fun `hasScope allows a scoped token with the requested scope`() {
        assertTrue(evaluator.hasScope(scopedAuthContext(ApiTokenScopes.CONTENT_VIEW.name), ApiTokenScopes.CONTENT_VIEW.name))
    }

    @Test
    fun `hasScope rejects a scoped token without the requested scope`() {
        assertFalse(evaluator.hasScope(scopedAuthContext(ApiTokenScopes.CONTENT_EDIT.name), ApiTokenScopes.CONTENT_VIEW.name))
    }

    @Test
    fun `hasScope rejects an anonymous caller`() {
        assertFalse(evaluator.hasScope(null, ApiTokenScopes.CONTENT_VIEW.name))
    }

    // --- hasAdminGroup ---

    @Test
    fun `hasAdminGroup returns true for administrators`() {
        assertTrue(evaluator.hasAdminGroup(authContext("administrators")))
    }

    @Test
    fun `hasAdminGroup returns false for editors`() {
        assertFalse(evaluator.hasAdminGroup(authContext("editors")))
    }

    @Test
    fun `hasAdminGroup returns false for null authentication`() {
        assertFalse(evaluator.hasAdminGroup(null))
    }

    // --- hasSaGroup ---

    @Test
    fun `hasSaGroup returns true for sa`() {
        assertTrue(evaluator.hasSaGroup(authContext("sa")))
    }

    @Test
    fun `hasSaGroup returns true for administrators`() {
        assertTrue(evaluator.hasSaGroup(authContext("administrators")))
    }

    @Test
    fun `hasSaGroup returns false for editors`() {
        assertFalse(evaluator.hasSaGroup(authContext("editors")))
    }

    @Test
    fun `hasSaGroup returns false for null authentication`() {
        assertFalse(evaluator.hasSaGroup(null))
    }

    // --- hasEditorGroup ---

    @Test
    fun `hasEditorGroup returns true for editors`() {
        assertTrue(evaluator.hasEditorGroup(authContext("editors")))
    }

    @Test
    fun `hasEditorGroup returns true for administrators`() {
        assertTrue(evaluator.hasEditorGroup(authContext("administrators")))
    }

    @Test
    fun `hasEditorGroup returns true for sa`() {
        assertTrue(evaluator.hasEditorGroup(authContext("sa")))
    }

    @Test
    fun `hasEditorGroup returns true for managers`() {
        assertTrue(evaluator.hasEditorGroup(authContext("managers")))
    }

    @Test
    fun `hasEditorGroup returns false for regular user`() {
        assertFalse(evaluator.hasEditorGroup(authContext("users")))
    }

    @Test
    fun `hasEditorGroup returns false for null authentication`() {
        assertFalse(evaluator.hasEditorGroup(null))
    }

    // --- hasManagerGroup ---

    @Test
    fun `hasManagerGroup returns true for managers`() {
        assertTrue(evaluator.hasManagerGroup(authContext("managers")))
    }

    @Test
    fun `hasManagerGroup returns true for administrators`() {
        assertTrue(evaluator.hasManagerGroup(authContext("administrators")))
    }

    @Test
    fun `hasManagerGroup returns false for editors`() {
        assertFalse(evaluator.hasManagerGroup(authContext("editors")))
    }

    @Test
    fun `hasManagerGroup returns false for null authentication`() {
        assertFalse(evaluator.hasManagerGroup(null))
    }

    // --- hasMessagingAccess ---

    @Test
    fun `hasMessagingAccess allows authenticated users without messaging group`() {
        assertTrue(evaluator.hasMessagingAccess(authContext("users")))
    }

    @Test
    fun `hasMessagingAccess returns false for null authentication`() {
        assertFalse(evaluator.hasMessagingAccess(null))
    }

    @Test
    fun `hasMessagingAccess allows scoped tokens without messaging scope`() {
        val principal = Principal(id = UUID.random())
        val authentication = object : AuthenticationContext(null, null) {
            override fun principal() = ScopedAuthenticatedPrincipal(
                principal = principal,
                allGroups = emptyList(),
                scopes = listOf("content:view"),
                allowedGroupIds = null,
                credentialId = 1L,
            )
        }

        assertTrue(evaluator.hasMessagingAccess(authentication))
    }

    // --- hasGroup ---

    @Test
    fun `hasGroup returns true when user has the specified group`() {
        assertTrue(evaluator.hasGroup(authContext("custom-group"), "custom-group"))
    }

    @Test
    fun `hasGroup returns true for administrators regardless of group`() {
        assertTrue(evaluator.hasGroup(authContext("administrators"), "any-group"))
    }

    @Test
    fun `hasGroup returns false when user does not have group`() {
        assertFalse(evaluator.hasGroup(authContext("editors"), "custom-group"))
    }

    @Test
    fun `hasGroup returns false for null authentication`() {
        assertFalse(evaluator.hasGroup(null, "custom-group"))
    }

    // --- verify methods ---

    @Test
    fun `verifyHasGroup throws SecurityException when user lacks group`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasGroup(authContext("editors"), "custom-group")
        }
    }

    @Test
    fun `verifyHasGroup succeeds when user has group`() {
        evaluator.verifyHasGroup(authContext("custom-group"), "custom-group")
    }

    @Test
    fun `verifyHasScope succeeds for a token with the requested scope`() {
        evaluator.verifyHasScope(scopedAuthContext(ApiTokenScopes.CONTENT_VIEW.name), ApiTokenScopes.CONTENT_VIEW.name)
    }

    @Test
    fun `verifyHasScope throws when token lacks the requested scope`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasScope(scopedAuthContext(ApiTokenScopes.CONTENT_EDIT.name), ApiTokenScopes.CONTENT_VIEW.name)
        }
    }

    @Test
    fun `verifyHasAdminGroup throws SecurityException when not admin`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasAdminGroup(authContext("editors"))
        }
    }

    @Test
    fun `verifyHasAdminGroup succeeds for admin`() {
        evaluator.verifyHasAdminGroup(authContext("administrators"))
    }

    @Test
    fun `verifyHasEditorGroup throws SecurityException when not editor`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasEditorGroup(authContext("users"))
        }
    }

    @Test
    fun `verifyHasEditorGroup succeeds for editor`() {
        evaluator.verifyHasEditorGroup(authContext("editors"))
    }

    @Test
    fun `verifyHasManagerGroup throws SecurityException when not manager`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasManagerGroup(authContext("editors"))
        }
    }

    @Test
    fun `verifyHasManagerGroup succeeds for manager`() {
        evaluator.verifyHasManagerGroup(authContext("managers"))
    }

    @Test
    fun `verifyHasMessagingAccess succeeds without messaging group`() {
        evaluator.verifyHasMessagingAccess(authContext("users"))
    }

    @Test
    fun `verifyHasMessagingAccess throws for anonymous authentication`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasMessagingAccess(null)
        }
    }

    @Test
    fun `verifyHasSaGroup throws SecurityException when not sa`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasSaGroup(authContext("editors"))
        }
    }

    @Test
    fun `verifyHasSaGroup succeeds for sa`() {
        evaluator.verifyHasSaGroup(authContext("sa"))
    }

    @Test
    fun `verifyHasGroup throws for null authentication`() {
        assertFailsWith<SecurityException> {
            evaluator.verifyHasGroup(null, "any-group")
        }
    }
}
