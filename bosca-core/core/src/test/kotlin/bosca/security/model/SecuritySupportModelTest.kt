package bosca.security.model

import bosca.security.SecureTokens
import java.time.OffsetDateTime
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SecuritySupportModelTest {

    private fun assertIdentityAndTypeBranches(value: Any) {
        assertEquals(value, value)
        assertFalse(value.equals(Any()))
        assertFalse(value.equals(null))
    }

    @Test
    fun `permission and signup transport models preserve values and equality contracts`() {
        val entity = Uuid.random()
        val group = Uuid.random()
        val input = PermissionInput(PermissionAction.EDIT, entity, group)
        assertEquals(entity, input.entityId)
        assertEquals(group, input.groupId)
        assertIdentityAndTypeBranches(input)
        assertNotEquals(input, input.copy(action = PermissionAction.VIEW))

        val signup = SignupToken(SignupTokenType.ORGANIZATION, "token")
        assertEquals("token", signup.token)
        assertIdentityAndTypeBranches(signup)
        assertNotEquals(signup, signup.copy(type = SignupTokenType.COMMUNITY_GROUP))

        assertIdentityAndTypeBranches(Permission(group, PermissionAction.VIEW))
        assertIdentityAndTypeBranches(PrincipalGroup(Uuid.random(), group))
    }

    @Test
    fun `exchange tokens preserve explicit and default expiry windows`() {
        val created = OffsetDateTime.parse("2025-01-01T00:00:00Z")
        val expires = created.plusMinutes(5)
        val explicit = ExchangeToken(Uuid.random(), "exchange", created, expires)
        assertEquals(created, explicit.created)
        assertEquals(expires, explicit.expires)
        assertIdentityAndTypeBranches(explicit)

        val defaulted = ExchangeToken(Uuid.random(), "default")
        assertTrue(defaulted.expires.isAfter(defaulted.created))
        assertTrue(defaulted.expires.isBefore(defaulted.created.plusMinutes(6)))
    }

    @Test
    fun `secure tokens provide 256 bits without padding`() {
        val first = SecureTokens.generate()
        val second = SecureTokens.generate()

        assertEquals(43, first.length)
        assertEquals(32, Base64.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(first).size)
        assertFalse('=' in first)
        assertNotEquals(first, second)
    }
}
