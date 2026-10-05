package bosca.security.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SecurityModelTest {

    // --- Permission ---

    @Test
    fun `Permission stores groupId and action`() {
        val groupId = Uuid.random()
        val permission = Permission(groupId = groupId, action = PermissionAction.VIEW)
        assertEquals(groupId, permission.groupId)
        assertEquals(PermissionAction.VIEW, permission.action)
    }

    @Test
    fun `Permission entityId throws UnsupportedOperationException`() {
        val permission = Permission(groupId = Uuid.random(), action = PermissionAction.EDIT)
        assertFailsWith<UnsupportedOperationException> {
            permission.entityId
        }
    }

    @Test
    fun `Permission equality is based on groupId and action`() {
        val groupId = Uuid.random()
        val p1 = Permission(groupId = groupId, action = PermissionAction.DELETE)
        val p2 = Permission(groupId = groupId, action = PermissionAction.DELETE)
        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
    }

    @Test
    fun `Permission inequality when action differs`() {
        val groupId = Uuid.random()
        val p1 = Permission(groupId = groupId, action = PermissionAction.VIEW)
        val p2 = Permission(groupId = groupId, action = PermissionAction.EDIT)
        assertNotEquals(p1, p2)
    }

    @Test
    fun `Permission copy creates independent instance with changed field`() {
        val permission = Permission(groupId = Uuid.random(), action = PermissionAction.LIST)
        val copied = permission.copy(action = PermissionAction.MANAGE)
        assertEquals(PermissionAction.MANAGE, copied.action)
        assertEquals(permission.groupId, copied.groupId)
    }

    // --- Group ---

    @Test
    fun `Group stores all properties`() {
        val id = Uuid.random()
        val group = Group(id = id, name = "admins", description = "Administrator group", type = GroupType.SYSTEM)
        assertEquals(id, group.id)
        assertEquals("admins", group.name)
        assertEquals("Administrator group", group.description)
        assertEquals(GroupType.SYSTEM, group.type)
    }

    @Test
    fun `Group id defaults to NIL`() {
        val group = Group(name = "test", description = "desc", type = GroupType.PRINCIPAL)
        assertEquals(Uuid.NIL, group.id)
    }

    @Test
    fun `Group equality is based on all fields`() {
        val id = Uuid.random()
        val g1 = Group(id = id, name = "g", description = "d", type = GroupType.SYSTEM)
        val g2 = Group(id = id, name = "g", description = "d", type = GroupType.SYSTEM)
        assertEquals(g1, g2)
        assertEquals(g1.hashCode(), g2.hashCode())
    }

    @Test
    fun `Group inequality when name differs`() {
        val id = Uuid.random()
        val g1 = Group(id = id, name = "a", description = "d", type = GroupType.SYSTEM)
        val g2 = Group(id = id, name = "b", description = "d", type = GroupType.SYSTEM)
        assertNotEquals(g1, g2)
    }

    @Test
    fun `Group copy allows changing fields`() {
        val group = Group(name = "old", description = "old desc", type = GroupType.PRINCIPAL)
        val copied = group.copy(name = "new", description = "new desc")
        assertEquals("new", copied.name)
        assertEquals("new desc", copied.description)
        assertEquals(group.type, copied.type)
    }

    // --- Token ---

    @Test
    fun `Token stores all properties`() {
        val token = Token(expiresAt = 1000, issuedAt = 500, token = "jwt-token-value")
        assertEquals(1000, token.expiresAt)
        assertEquals(500, token.issuedAt)
        assertEquals("jwt-token-value", token.token)
    }

    @Test
    fun `Token equality is based on all fields`() {
        val t1 = Token(expiresAt = 100, issuedAt = 50, token = "abc")
        val t2 = Token(expiresAt = 100, issuedAt = 50, token = "abc")
        assertEquals(t1, t2)
        assertEquals(t1.hashCode(), t2.hashCode())
    }

    @Test
    fun `Token inequality when token string differs`() {
        val t1 = Token(expiresAt = 100, issuedAt = 50, token = "abc")
        val t2 = Token(expiresAt = 100, issuedAt = 50, token = "def")
        assertNotEquals(t1, t2)
    }

    @Test
    fun `Token copy changes only specified fields`() {
        val token = Token(expiresAt = 100, issuedAt = 50, token = "abc")
        val copied = token.copy(token = "xyz")
        assertEquals("xyz", copied.token)
        assertEquals(100, copied.expiresAt)
        assertEquals(50, copied.issuedAt)
    }

    // --- LoginResponse ---

    @Test
    fun `LoginResponse stores all properties`() {
        val principalId = Uuid.random()
        val token = Token(expiresAt = 100, issuedAt = 50, token = "t")
        val response = LoginResponse(principalId = principalId, refreshToken = "refresh", token = token)
        assertEquals(principalId, response.principalId)
        assertEquals("refresh", response.refreshToken)
        assertEquals(token, response.token)
    }

    @Test
    fun `LoginResponse refreshToken can be null`() {
        val response = LoginResponse(
            principalId = Uuid.random(),
            refreshToken = null,
            token = Token(expiresAt = 100, issuedAt = 50, token = "t")
        )
        assertNull(response.refreshToken)
    }

    @Test
    fun `LoginResponse equality is based on all fields`() {
        val id = Uuid.random()
        val token = Token(expiresAt = 100, issuedAt = 50, token = "t")
        val r1 = LoginResponse(principalId = id, refreshToken = "r", token = token)
        val r2 = LoginResponse(principalId = id, refreshToken = "r", token = token)
        assertEquals(r1, r2)
    }

    // --- PrincipalGroup ---

    @Test
    fun `PrincipalGroup stores principal and groupId`() {
        val principal = Uuid.random()
        val groupId = Uuid.random()
        val pg = PrincipalGroup(principal = principal, groupId = groupId)
        assertEquals(principal, pg.principal)
        assertEquals(groupId, pg.groupId)
    }

    @Test
    fun `PrincipalGroup equality is based on both fields`() {
        val principal = Uuid.random()
        val groupId = Uuid.random()
        val pg1 = PrincipalGroup(principal = principal, groupId = groupId)
        val pg2 = PrincipalGroup(principal = principal, groupId = groupId)
        assertEquals(pg1, pg2)
        assertEquals(pg1.hashCode(), pg2.hashCode())
    }

    @Test
    fun `PrincipalGroup inequality when groupId differs`() {
        val principal = Uuid.random()
        val pg1 = PrincipalGroup(principal = principal, groupId = Uuid.random())
        val pg2 = PrincipalGroup(principal = principal, groupId = Uuid.random())
        assertNotEquals(pg1, pg2)
    }

    // --- PrincipalCredentialAndType ---

    @Test
    fun `PrincipalCredentialAndType stores identifier and type`() {
        val cred = PrincipalCredentialAndType(identifier = "user@example.com", type = CredentialType.PASSWORD)
        assertEquals("user@example.com", cred.identifier)
        assertEquals(CredentialType.PASSWORD, cred.type)
    }

    @Test
    fun `PrincipalCredentialAndType equality is based on both fields`() {
        val c1 = PrincipalCredentialAndType(identifier = "id", type = CredentialType.OAUTH2)
        val c2 = PrincipalCredentialAndType(identifier = "id", type = CredentialType.OAUTH2)
        assertEquals(c1, c2)
        assertEquals(c1.hashCode(), c2.hashCode())
    }

    @Test
    fun `PrincipalCredentialAndType inequality when type differs`() {
        val c1 = PrincipalCredentialAndType(identifier = "id", type = CredentialType.PASSWORD)
        val c2 = PrincipalCredentialAndType(identifier = "id", type = CredentialType.PASSWORD_SCRYPT)
        assertNotEquals(c1, c2)
    }

    @Test
    fun `PrincipalCredentialAndType copy changes only specified fields`() {
        val cred = PrincipalCredentialAndType(identifier = "user", type = CredentialType.PASSWORD)
        val copied = cred.copy(type = CredentialType.OAUTH2)
        assertEquals("user", copied.identifier)
        assertEquals(CredentialType.OAUTH2, copied.type)
    }

    // --- RefreshToken ---

    @Test
    fun `RefreshToken stores principalId and token`() {
        val principalId = Uuid.random()
        val rt = RefreshToken(principalId = principalId, token = "refresh-token-value")
        assertEquals(principalId, rt.principalId)
        assertEquals("refresh-token-value", rt.token)
    }

    @Test
    fun `RefreshToken has default created and expires timestamps`() {
        val rt = RefreshToken(principalId = Uuid.random(), token = "t")
        assertTrue(rt.expires.isAfter(rt.created))
    }

    @Test
    fun `RefreshToken equality is based on all fields`() {
        val principalId = Uuid.random()
        val created = java.time.OffsetDateTime.now()
        val expires = created.plusDays(30)
        val rt1 = RefreshToken(principalId = principalId, token = "t", created = created, expires = expires)
        val rt2 = RefreshToken(principalId = principalId, token = "t", created = created, expires = expires)
        assertEquals(rt1, rt2)
        assertEquals(rt1.hashCode(), rt2.hashCode())
    }

    // --- Principal ---

    @Test
    fun `Principal id defaults to NIL`() {
        val principal = Principal()
        assertEquals(Uuid.NIL, principal.id)
    }

    @Test
    fun `Principal has correct default values`() {
        val principal = Principal()
        assertFalse(principal.verified)
        assertTrue(principal.anonymous)
        assertNull(principal.attributes)
        assertNull(principal.verificationToken)
        assertNull(principal.primaryProfileId)
    }

    @Test
    fun `Principal toString returns id as string`() {
        val id = Uuid.random()
        val principal = Principal(id = id)
        assertEquals(id.toString(), principal.toString())
    }

    @Test
    fun `Principal copy allows changing fields`() {
        val principal = Principal(verified = false, anonymous = true)
        val copied = principal.copy(verified = true, anonymous = false)
        assertTrue(copied.verified)
        assertFalse(copied.anonymous)
    }

    @Test
    fun `Principal equality is based on all fields`() {
        val id = Uuid.random()
        val now = java.time.OffsetDateTime.now()
        val p1 = Principal(id = id, created = now, modified = now, verified = true, anonymous = false)
        val p2 = Principal(id = id, created = now, modified = now, verified = true, anonymous = false)
        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
    }

    // --- AuthenticatedPrincipal ---

    @Test
    fun `AuthenticatedPrincipal id delegates to principal`() {
        val id = Uuid.random()
        val principal = Principal(id = id)
        val auth = AuthenticatedPrincipal(principal, emptyList())
        assertEquals(id, auth.id)
    }

    @Test
    fun `AuthenticatedPrincipal hasGroup by Group object`() {
        val group = Group(id = Uuid.random(), name = "admins", description = "Admin", type = GroupType.SYSTEM)
        val auth = AuthenticatedPrincipal(Principal(), listOf(group))
        assertTrue(auth.hasGroup(group))
    }

    @Test
    fun `AuthenticatedPrincipal hasGroup by UUID`() {
        val groupId = Uuid.random()
        val group = Group(id = groupId, name = "admins", description = "Admin", type = GroupType.SYSTEM)
        val auth = AuthenticatedPrincipal(Principal(), listOf(group))
        assertTrue(auth.hasGroup(groupId))
        assertFalse(auth.hasGroup(Uuid.random()))
    }

    @Test
    fun `AuthenticatedPrincipal hasGroup by name`() {
        val group = Group(id = Uuid.random(), name = "editors", description = "Editors", type = GroupType.PRINCIPAL)
        val auth = AuthenticatedPrincipal(Principal(), listOf(group))
        assertTrue(auth.hasGroup("editors"))
        assertFalse(auth.hasGroup("admins"))
    }

    @Test
    fun `AuthenticatedPrincipal asPrincipal returns underlying principal`() {
        val principal = Principal(id = Uuid.random(), verified = true)
        val auth = AuthenticatedPrincipal(principal, emptyList())
        assertEquals(principal, auth.asPrincipal())
    }

    @Test
    fun `AuthenticatedPrincipal equality is based on id only`() {
        val id = Uuid.random()
        val p1 = Principal(id = id)
        val p2 = Principal(id = id, verified = true)
        val group = Group(id = Uuid.random(), name = "g", description = "d", type = GroupType.SYSTEM)
        val auth1 = AuthenticatedPrincipal(p1, emptyList())
        val auth2 = AuthenticatedPrincipal(p2, listOf(group))
        assertEquals(auth1, auth2)
        assertEquals(auth1.hashCode(), auth2.hashCode())
    }

    @Test
    fun `AuthenticatedPrincipal inequality when id differs`() {
        val auth1 = AuthenticatedPrincipal(Principal(id = Uuid.random()), emptyList())
        val auth2 = AuthenticatedPrincipal(Principal(id = Uuid.random()), emptyList())
        assertNotEquals(auth1, auth2)
        assertEquals(auth1, auth1)
        assertFalse(auth1.equals(null))
        assertFalse(auth1.equals("principal"))
    }
}
