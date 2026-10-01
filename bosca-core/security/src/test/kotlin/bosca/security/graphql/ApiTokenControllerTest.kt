package bosca.security.graphql

import bosca.security.model.ApiToken
import bosca.security.model.ApiTokenCredentialAttributes
import bosca.security.model.PrincipalCredential
import bosca.serialization.UUID
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTokenControllerTest {

    private val controller = ApiTokenController()
    private val principalId = UUID.random()

    @Test
    fun `id returns credential id`() {
        val view = createApiToken(id = 42L)
        assertEquals(42L, controller.id(view))
    }

    @Test
    fun `principalId returns credential principal`() {
        val view = createApiToken()
        assertEquals(principalId, controller.principalId(view))
    }

    @Test
    fun `name returns token name from attributes`() {
        val view = createApiToken(name = "My CI Token")
        assertEquals("My CI Token", controller.name(view))
    }

    @Test
    fun `description returns null when not set`() {
        val view = createApiToken(description = null)
        assertNull(controller.description(view))
    }

    @Test
    fun `description returns value when set`() {
        val view = createApiToken(description = "For GitHub Actions")
        assertEquals("For GitHub Actions", controller.description(view))
    }

    @Test
    fun `tokenPrefix returns first 12 chars`() {
        val view = createApiToken(tokenPrefix = "bsk_a1b2c3d4")
        assertEquals("bsk_a1b2c3d4", controller.tokenPrefix(view))
    }

    @Test
    fun `scopes returns null when unrestricted`() {
        val view = createApiToken(scopes = null)
        assertNull(controller.scopes(view))
    }

    @Test
    fun `scopes returns list when restricted`() {
        val view = createApiToken(scopes = listOf("content:view", "storage:read"))
        assertEquals(listOf("content:view", "storage:read"), controller.scopes(view))
    }

    @Test
    fun `allowedGroups returns parsed UUIDs`() {
        val groupId = UUID.random()
        val view = createApiToken(allowedGroups = listOf(groupId.toString()))
        val result = controller.allowedGroups(view)
        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals(groupId, result[0])
    }

    @Test
    fun `allowedGroups returns null when unrestricted`() {
        val view = createApiToken(allowedGroups = null)
        assertNull(controller.allowedGroups(view))
    }

    @Test
    fun `active returns true for non-revoked non-expired token`() {
        val view = createApiToken(revokedAt = null, expiresAt = null)
        assertTrue(controller.active(view))
    }

    @Test
    fun `active returns false for revoked token`() {
        val view = createApiToken(revokedAt = OffsetDateTime.now(ZoneOffset.UTC).toString())
        assertFalse(controller.active(view))
    }

    @Test
    fun `active returns false for expired token`() {
        val view = createApiToken(expiresAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString())
        assertFalse(controller.active(view))
    }

    @Test
    fun `active returns true for token with future expiry`() {
        val view = createApiToken(expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).toString())
        assertTrue(controller.active(view))
    }

    @Test
    fun `active returns false for a malformed expiry`() {
        assertFalse(controller.active(createApiToken(expiresAt = "not-a-timestamp")))
    }

    @Test
    fun `expiresAt returns parsed timestamp`() {
        val timestamp = "2027-01-15T00:00:00Z"
        val view = createApiToken(expiresAt = timestamp)
        val result = controller.expiresAt(view)
        assertNotNull(result)
    }

    @Test
    fun `optional timestamp fields return null when absent`() {
        val view = createApiToken()

        assertNull(controller.expiresAt(view))
        assertNull(controller.lastUsedAt(view))
        assertNull(controller.revokedAt(view))
    }

    @Test
    fun `lastUsedAt returns null when never used`() {
        val view = createApiToken(lastUsedAt = null)
        assertNull(controller.lastUsedAt(view))
    }

    @Test
    fun `lastUsedIp returns IP when set`() {
        val view = createApiToken(lastUsedIp = "192.168.1.1")
        assertEquals("192.168.1.1", controller.lastUsedIp(view))
    }

    @Test
    fun `timestamp fields accept valid values and safely reject malformed values`() {
        val timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString()
        val valid = createApiToken(
            expiresAt = timestamp,
            lastUsedAt = timestamp,
            revokedAt = timestamp,
        )
        assertNotNull(controller.expiresAt(valid))
        assertNotNull(controller.lastUsedAt(valid))
        assertNotNull(controller.revokedAt(valid))

        val malformed = createApiToken(
            expiresAt = "invalid",
            lastUsedAt = "invalid",
            revokedAt = "invalid",
        )
        assertNull(controller.expiresAt(malformed))
        assertNull(controller.lastUsedAt(malformed))
        assertNull(controller.revokedAt(malformed))
        assertFalse(controller.active(malformed))
    }

    private fun createApiToken(
        id: Long = 1L,
        name: String = "Test Token",
        description: String? = null,
        tokenPrefix: String = "bsk_a1b2c3d4",
        scopes: List<String>? = null,
        allowedGroups: List<String>? = null,
        expiresAt: String? = null,
        lastUsedAt: String? = null,
        lastUsedIp: String? = null,
        revokedAt: String? = null,
    ): ApiToken {
        val attrs = ApiTokenCredentialAttributes(
            identifier = "sha256:testhash",
            name = name,
            description = description,
            tokenPrefix = tokenPrefix,
            scopes = scopes,
            allowedGroups = allowedGroups,
            expiresAt = expiresAt,
            lastUsedAt = lastUsedAt,
            lastUsedIp = lastUsedIp,
            revokedAt = revokedAt,
            createdBy = principalId.toString(),
        )
        val credential = PrincipalCredential(principal = principalId, attributes = attrs).copy(id = id)
        return ApiToken(credential)
    }
}
