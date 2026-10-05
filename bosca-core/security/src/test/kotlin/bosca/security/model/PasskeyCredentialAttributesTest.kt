package bosca.security.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class PasskeyCredentialAttributesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serialization roundtrip preserves all fields`() {
        val attrs = PasskeyCredentialAttributes(
            identifier = "credential-id-base64url",
            name = "MacBook Pro Touch ID",
            publicKeyCose = "cose-key-base64url",
            signCount = 42,
            aaguid = "aaguid-string",
            transports = listOf("internal", "hybrid"),
            lastUsedAt = "2026-05-03T10:00:00Z",
            createdAt = "2026-05-01T08:00:00Z",
        )

        val serialized = json.encodeToJsonElement(PasskeyCredentialAttributes.serializer(), attrs)
        val deserialized = json.decodeFromJsonElement(PasskeyCredentialAttributes.serializer(), serialized)

        assertEquals(attrs.identifier, deserialized.identifier)
        assertEquals(attrs.name, deserialized.name)
        assertEquals(attrs.publicKeyCose, deserialized.publicKeyCose)
        assertEquals(attrs.signCount, deserialized.signCount)
        assertEquals(attrs.aaguid, deserialized.aaguid)
        assertEquals(attrs.transports, deserialized.transports)
        assertEquals(attrs.lastUsedAt, deserialized.lastUsedAt)
        assertEquals(attrs.createdAt, deserialized.createdAt)
    }

    @Test
    fun `type is PASSKEY`() {
        val attrs = PasskeyCredentialAttributes(
            identifier = "cred-id",
            name = "Test Key",
            publicKeyCose = "key-data",
            createdAt = "2026-05-01T08:00:00Z",
        )
        assertEquals(CredentialType.PASSKEY, attrs.type)
    }

    @Test
    fun `withIdentifier creates copy with new identifier`() {
        val attrs = PasskeyCredentialAttributes(
            identifier = "old-id",
            name = "Key",
            publicKeyCose = "key-data",
            createdAt = "2026-05-01T08:00:00Z",
        )
        val updated = attrs.withIdentifier("new-id")
        assertIs<PasskeyCredentialAttributes>(updated)
        assertEquals("new-id", updated.identifier)
        assertEquals("Key", (updated as PasskeyCredentialAttributes).name)
    }

    @Test
    fun `PrincipalCredential roundtrip with PASSKEY type`() {
        val principalId = Uuid.random()
        val attrs = PasskeyCredentialAttributes(
            identifier = "credential-id",
            name = "Security Key",
            publicKeyCose = "cose-public-key-data",
            signCount = 5,
            transports = listOf("usb"),
            createdAt = "2026-05-01T00:00:00Z",
        )

        val credential = PrincipalCredential(principal = principalId, attributes = attrs)

        assertEquals(CredentialType.PASSKEY, credential.type)
        assertEquals(principalId, credential.principal)

        val decoded = credential.attributes
        assertIs<PasskeyCredentialAttributes>(decoded)
        assertEquals("credential-id", decoded.identifier)
        assertEquals("Security Key", decoded.name)
        assertEquals("cose-public-key-data", decoded.publicKeyCose)
        assertEquals(5L, decoded.signCount)
        assertEquals(listOf("usb"), decoded.transports)
    }

    @Test
    fun `deserialization with missing optional fields uses defaults`() {
        val jsonStr = """{"identifier":"cred","name":"Key","public_key_cose":"pk","created_at":"2026-01-01T00:00:00Z"}"""
        val attrs = json.decodeFromString(PasskeyCredentialAttributes.serializer(), jsonStr)

        assertEquals(0L, attrs.signCount)
        assertEquals(null, attrs.aaguid)
        assertEquals(emptyList(), attrs.transports)
        assertEquals(null, attrs.lastUsedAt)
    }
}
