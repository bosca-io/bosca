package bosca.security.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Verifies construction, attribute serialization/deserialization round-trips,
 * and mutation methods of [PrincipalCredential] and its associated
 * [CredentialAttributes] implementations.
 */
class PrincipalCredentialTest {

    private fun pwHash(value: String): HashedEncodedPassword =
        object : HashedEncodedPassword { override val hash = value }

    // --- SimplePasswordAttributes ---

    @Test
    fun `SimplePasswordAttributes normalizes identifiers and upgrades hashed passwords`() {
        val attributes = SimplePasswordAttributes(identifier = "USER@Test.COM", password = "plain")
        assertEquals(CredentialType.PASSWORD, attributes.type)
        assertEquals("new@test.com", attributes.withIdentifier("  NEW@Test.COM ").identifier)

        val upgraded = attributes.withPassword(pwHash("hashed"))
        assertEquals("user@test.com", upgraded.identifier)
        assertEquals("hashed", upgraded.password)
    }

    @Test
    fun `SimplePasswordAttributes cannot be persisted directly`() {
        assertFailsWith<IllegalStateException> {
            PrincipalCredential(
                principal = Uuid.random(),
                attributes = SimplePasswordAttributes("user@test.com", "plain"),
            )
        }
    }

    // --- CredentialPasswordAttributes ---

    @Test
    fun `CredentialPasswordAttributes stores identifier and password`() {
        val attrs = CredentialPasswordAttributes(identifier = "user@example.com", password = pwHash("hashed123"))
        assertEquals("user@example.com", attrs.identifier)
        assertEquals("hashed123", attrs.password)
        assertEquals(CredentialType.PASSWORD, attrs.type)
    }

    @Test
    fun `CredentialPasswordAttributes constructor with HashedEncodedPassword`() {
        val hashed = object : HashedEncodedPassword {
            override val hash = "scrypt-hash-value"
        }
        val attrs = CredentialPasswordAttributes(identifier = "user@test.com", password = hashed)
        assertEquals("scrypt-hash-value", attrs.password)
    }

    @Test
    fun `CredentialPasswordAttributes withIdentifier lowercases and trims`() {
        val attrs = CredentialPasswordAttributes(identifier = "old@test.com", password = pwHash("pw"))
        val updated = attrs.withIdentifier("  NEW@Test.COM  ")
        assertEquals("new@test.com", updated.identifier)
        assertEquals("pw", updated.password)
    }

    @Test
    fun `CredentialPasswordAttributes withPassword updates password`() {
        val attrs = CredentialPasswordAttributes(identifier = "user@test.com", password = pwHash("old"))
        val hashed = object : HashedEncodedPassword {
            override val hash = "new-hash"
        }
        val updated = attrs.withPassword(hashed)
        assertEquals("new-hash", updated.password)
        assertEquals("user@test.com", updated.identifier)
    }

    // --- OAuth2CredentialAttributes ---

    @Test
    fun `OAuth2CredentialAttributes stores all fields with defaults`() {
        val attrs = OAuth2CredentialAttributes(identifier = "oauth-id")
        assertEquals("oauth-id", attrs.identifier)
        assertNull(attrs.localId)
        assertNull(attrs.tokens)
        assertNull(attrs.source)
        assertEquals(CredentialType.OAUTH2, attrs.type)
    }

    @Test
    fun `OAuth2CredentialAttributes stores all explicit fields`() {
        val attrs = OAuth2CredentialAttributes(
            identifier = "oauth-id",
            localId = "local-123",
            tokens = "token-json",
            source = "google"
        )
        assertEquals("local-123", attrs.localId)
        assertEquals("token-json", attrs.tokens)
        assertEquals("google", attrs.source)
    }

    @Test
    fun `OAuth2CredentialAttributes withIdentifier throws UnsupportedOperationException`() {
        val attrs = OAuth2CredentialAttributes(identifier = "id")
        assertFailsWith<UnsupportedOperationException> {
            attrs.withIdentifier("new-id")
        }
    }

    @Test
    fun `OAuth2CredentialAttributes withPassword throws UnsupportedOperationException`() {
        val attrs = OAuth2CredentialAttributes(identifier = "id")
        val hashed = object : HashedEncodedPassword {
            override val hash = "hash"
        }
        assertFailsWith<UnsupportedOperationException> {
            attrs.withPassword(hashed)
        }
    }

    // --- ScryptCredentialAttributes ---

    @Test
    fun `ScryptCredentialAttributes stores all fields`() {
        val attrs = ScryptCredentialAttributes(
            salt = "salt-value",
            identifier = "user@test.com",
            passwordHash = "hash-value"
        )
        assertEquals("salt-value", attrs.salt)
        assertEquals("user@test.com", attrs.identifier)
        assertEquals("hash-value", attrs.passwordHash)
        assertNull(attrs.localId)
        assertEquals(CredentialType.PASSWORD_SCRYPT, attrs.type)
    }

    @Test
    fun `ScryptCredentialAttributes withIdentifier lowercases and trims`() {
        val attrs = ScryptCredentialAttributes(salt = "s", identifier = "OLD@Test.COM", passwordHash = "h")
        val updated = attrs.withIdentifier("  New@Example.COM  ")
        assertEquals("new@example.com", updated.identifier)
    }

    @Test
    fun `ScryptCredentialAttributes withPassword converts to CredentialPasswordAttributes`() {
        val attrs = ScryptCredentialAttributes(salt = "s", identifier = "user@test.com", passwordHash = "h")
        val hashed = object : HashedEncodedPassword {
            override val hash = "new-hash"
        }
        val updated = attrs.withPassword(hashed)
        assertIs<CredentialPasswordAttributes>(updated)
        assertEquals("user@test.com", updated.identifier)
        assertEquals("new-hash", updated.password)
    }

    // --- PrincipalCredential ---

    @Test
    fun `PrincipalCredential stores id principal type and attributesJson`() {
        val principalId = Uuid.random()
        val attrs = CredentialPasswordAttributes(identifier = "user@test.com", password = pwHash("hash"))
        val cred = PrincipalCredential(principal = principalId, attributes = attrs)
        assertEquals(principalId, cred.principal)
        assertEquals(CredentialType.PASSWORD, cred.type)
        assertEquals(0L, cred.id)
    }

    @Test
    fun `PrincipalCredential attributes property deserializes PASSWORD correctly`() {
        val attrs = CredentialPasswordAttributes(identifier = "user@test.com", password = pwHash("hash"))
        val cred = PrincipalCredential(principal = Uuid.random(), attributes = attrs)
        val deserialized = cred.attributes
        assertIs<CredentialPasswordAttributes>(deserialized)
        assertEquals("user@test.com", deserialized.identifier)
        assertEquals("hash", deserialized.password)
    }

    @Test
    fun `PrincipalCredential attributes property deserializes OAUTH2 correctly`() {
        val attrs = OAuth2CredentialAttributes(identifier = "oauth-user", localId = "local-1", source = "github")
        val cred = PrincipalCredential(principal = Uuid.random(), attributes = attrs)
        val deserialized = cred.attributes
        assertIs<OAuth2CredentialAttributes>(deserialized)
        assertEquals("oauth-user", deserialized.identifier)
        assertEquals("local-1", deserialized.localId)
        assertEquals("github", deserialized.source)
    }

    @Test
    fun `PrincipalCredential attributes property deserializes PASSWORD_SCRYPT correctly`() {
        val attrs = ScryptCredentialAttributes(salt = "s", identifier = "user", passwordHash = "h")
        val cred = PrincipalCredential(principal = Uuid.random(), attributes = attrs)
        val deserialized = cred.attributes
        assertIs<ScryptCredentialAttributes>(deserialized)
        assertEquals("s", deserialized.salt)
        assertEquals("user", deserialized.identifier)
        assertEquals("h", deserialized.passwordHash)
    }

    @Test
    fun `PrincipalCredential withIdentifier updates identifier in attributes`() {
        val attrs = CredentialPasswordAttributes(identifier = "old@test.com", password = pwHash("hash"))
        val cred = PrincipalCredential(principal = Uuid.random(), attributes = attrs)
        val updated = cred.withIdentifier("  New@Example.COM  ")
        val updatedAttrs = updated.attributes
        assertIs<CredentialPasswordAttributes>(updatedAttrs)
        assertEquals("new@example.com", updatedAttrs.identifier)
    }

    @Test
    fun `PrincipalCredential withPassword updates password in attributes`() {
        val attrs = CredentialPasswordAttributes(identifier = "user@test.com", password = pwHash("old-hash"))
        val cred = PrincipalCredential(principal = Uuid.random(), attributes = attrs)
        val hashed = object : HashedEncodedPassword {
            override val hash = "new-hash"
        }
        val updated = cred.withPassword(hashed)
        val updatedAttrs = updated.attributes
        assertIs<CredentialPasswordAttributes>(updatedAttrs)
        assertEquals("new-hash", updatedAttrs.password)
    }

    @Test
    fun `PrincipalCredential withPasswordAndIdentifier updates both fields`() {
        val attrs = CredentialPasswordAttributes(identifier = "old@test.com", password = pwHash("old-hash"))
        val cred = PrincipalCredential(principal = Uuid.random(), attributes = attrs)
        val hashed = object : HashedEncodedPassword {
            override val hash = "new-hash"
        }
        val updated = cred.withPasswordAndIdentifier(hashed, "  New@Test.COM  ")
        val updatedAttrs = updated.attributes
        assertIs<CredentialPasswordAttributes>(updatedAttrs)
        assertEquals("new-hash", updatedAttrs.password)
        assertEquals("new@test.com", updatedAttrs.identifier)
    }

    @Test
    fun `PrincipalCredential equality is based on all data class fields`() {
        val principalId = Uuid.random()
        val attrs = CredentialPasswordAttributes(identifier = "user", password = pwHash("pw"))
        val c1 = PrincipalCredential(principal = principalId, attributes = attrs)
        val c2 = PrincipalCredential(principal = principalId, attributes = attrs)
        assertEquals(c1, c2)
        assertEquals(c1.hashCode(), c2.hashCode())
    }

    @Test
    fun `PrincipalCredential default id is zero`() {
        val cred = PrincipalCredential(
            principal = Uuid.random(),
            attributes = CredentialPasswordAttributes(identifier = "u", password = pwHash("p"))
        )
        assertEquals(0L, cred.id)
    }

    @Test
    fun `PrincipalCredential primary constructor preserves explicit originators`() {
        val attributes = Json.parseToJsonElement("""{"identifier":"oauth","local_id":null,"tokens":null,"type":null}""")
        val credential = PrincipalCredential(
            id = 17,
            principal = Uuid.random(),
            type = CredentialType.OAUTH2,
            attributesJson = attributes,
            originator = "original",
            lastOriginator = "latest",
        )

        assertEquals(17, credential.id)
        assertEquals("original", credential.originator)
        assertEquals("latest", credential.lastOriginator)
        assertIs<OAuth2CredentialAttributes>(credential.attributes)
    }
}
