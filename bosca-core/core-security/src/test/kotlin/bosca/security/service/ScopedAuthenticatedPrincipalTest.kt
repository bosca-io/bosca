package bosca.security.service

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopedAuthenticatedPrincipalTest {

    private val groupAdmin = Group(id = UUID.random(), name = "administrators", description = "Admins", type = GroupType.SYSTEM)
    private val groupEditors = Group(id = UUID.random(), name = "editors", description = "Editors", type = GroupType.PRINCIPAL)
    private val groupViewers = Group(id = UUID.random(), name = "viewers", description = "Viewers", type = GroupType.PRINCIPAL)
    private val allGroups = listOf(groupAdmin, groupEditors, groupViewers)
    private val principal = Principal(id = UUID.random(), anonymous = false, verified = true)

    @Test
    fun `unrestricted groups passes all group checks through`() {
        val scoped = ScopedAuthenticatedPrincipal(
            principal = principal,
            allGroups = allGroups,
            scopes = null,
            allowedGroupIds = null,
            credentialId = 1L,
        )

        assertTrue(scoped.hasGroup(groupAdmin))
        assertTrue(scoped.hasGroup(groupEditors))
        assertTrue(scoped.hasGroup(groupViewers))
        assertTrue(scoped.hasGroup("administrators"))
        assertTrue(scoped.hasGroup("editors"))
        assertTrue(scoped.hasGroup("viewers"))
    }

    @Test
    fun `restricted groups filters out disallowed groups`() {
        val scoped = ScopedAuthenticatedPrincipal(
            principal = principal,
            allGroups = allGroups,
            scopes = null,
            allowedGroupIds = setOf(groupEditors.id),
            credentialId = 1L,
        )

        assertFalse(scoped.hasGroup(groupAdmin))
        assertTrue(scoped.hasGroup(groupEditors))
        assertFalse(scoped.hasGroup(groupViewers))
        assertFalse(scoped.hasGroup("administrators"))
        assertTrue(scoped.hasGroup("editors"))
        assertFalse(scoped.hasGroup("viewers"))
    }

    @Test
    fun `empty allowed groups filters out everything`() {
        val scoped = ScopedAuthenticatedPrincipal(
            principal = principal,
            allGroups = allGroups,
            scopes = null,
            allowedGroupIds = emptySet(),
            credentialId = 1L,
        )

        assertFalse(scoped.hasGroup(groupAdmin))
        assertFalse(scoped.hasGroup(groupEditors))
        assertFalse(scoped.hasGroup(groupViewers))
    }

    @Test
    fun `hasScope returns true for matching scope`() {
        val scoped = ScopedAuthenticatedPrincipal(
            principal = principal,
            allGroups = allGroups,
            scopes = listOf("content:view", "storage:read"),
            allowedGroupIds = null,
            credentialId = 1L,
        )

        assertTrue(scoped.hasScope("content:view"))
        assertTrue(scoped.hasScope("storage:read"))
        assertFalse(scoped.hasScope("content:edit"))
        assertFalse(scoped.hasScope("security:manage"))
    }

    @Test
    fun `hasScope returns true for all scopes when unrestricted`() {
        val scoped = ScopedAuthenticatedPrincipal(
            principal = principal,
            allGroups = allGroups,
            scopes = null,
            allowedGroupIds = null,
            credentialId = 1L,
        )

        assertTrue(scoped.hasScope("anything"))
        assertTrue(scoped.hasScope("content:view"))
        assertTrue(scoped.hasScope("nonexistent:scope"))
    }

    @Test
    fun `hasScope denies all scopes when empty list`() {
        val scoped = ScopedAuthenticatedPrincipal(
            principal = principal,
            allGroups = allGroups,
            scopes = emptyList(),
            allowedGroupIds = null,
            credentialId = 1L,
        )

        assertFalse(scoped.hasScope("content:view"))
        assertFalse(scoped.hasScope("anything"))
    }

    @Test
    fun `scopes are deduplicated into a set`() {
        val scoped = ScopedAuthenticatedPrincipal(
            principal = principal,
            allGroups = allGroups,
            scopes = listOf("content:view", "content:view", "storage:read"),
            allowedGroupIds = null,
            credentialId = 1L,
        )

        assertEquals(2, scoped.scopes!!.size)
        assertTrue(scoped.hasScope("content:view"))
        assertTrue(scoped.hasScope("storage:read"))
    }

    @Test
    fun `id delegates to underlying principal`() {
        val scoped = ScopedAuthenticatedPrincipal(
            principal = principal,
            allGroups = allGroups,
            scopes = null,
            allowedGroupIds = null,
            credentialId = 42L,
        )

        assertEquals(principal.id, scoped.id)
        assertEquals(42L, scoped.credentialId)
    }

    @Test
    fun `asPrincipal returns the underlying principal`() {
        val scoped = ScopedAuthenticatedPrincipal(
            principal = principal,
            allGroups = allGroups,
            scopes = listOf("content:view"),
            allowedGroupIds = setOf(groupEditors.id),
            credentialId = 1L,
        )

        assertEquals(principal.id, scoped.asPrincipal().id)
    }

    @Test
    fun `combined scope and group restrictions work together`() {
        val scoped = ScopedAuthenticatedPrincipal(
            principal = principal,
            allGroups = allGroups,
            scopes = listOf("content:view"),
            allowedGroupIds = setOf(groupViewers.id),
            credentialId = 1L,
        )

        // Only viewers group
        assertFalse(scoped.hasGroup("administrators"))
        assertFalse(scoped.hasGroup("editors"))
        assertTrue(scoped.hasGroup("viewers"))

        // Only content:view scope
        assertTrue(scoped.hasScope("content:view"))
        assertFalse(scoped.hasScope("content:edit"))
    }
}
