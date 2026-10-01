package bosca.security.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

class PasskeyCredentialAttributesTest {

    private val json = Json { encodeDefaults = false }
    private val jsonWithDefaults = Json { encodeDefaults = true }

    @Test
    fun `minimal passkey applies defaults and round trips`() {
        val attributes = PasskeyCredentialAttributes(
            identifier = "credential-id",
            name = "Laptop",
            publicKeyCose = "public-key",
            createdAt = "2026-08-04T12:00:00Z",
        )

        assertEquals(0, attributes.signCount)
        assertNull(attributes.aaguid)
        assertEquals(emptyList(), attributes.transports)
        assertNull(attributes.lastUsedAt)
        assertEquals(CredentialType.PASSKEY, attributes.type)
        val encoded = json.encodeToString(PasskeyCredentialAttributes.serializer(), attributes)
        assertEquals(attributes, json.decodeFromString(PasskeyCredentialAttributes.serializer(), encoded))
    }

    @Test
    fun `complete passkey supports explicit values and credential restoration`() {
        val attributes = PasskeyCredentialAttributes(
            identifier = "credential-id",
            name = "Security key",
            publicKeyCose = "public-key",
            signCount = 42,
            aaguid = "aaguid",
            transports = listOf("usb", "nfc"),
            lastUsedAt = "2026-08-04T13:00:00Z",
            createdAt = "2026-08-04T12:00:00Z",
        )

        val encoded = jsonWithDefaults.encodeToString(PasskeyCredentialAttributes.serializer(), attributes)
        assertEquals(attributes, jsonWithDefaults.decodeFromString(PasskeyCredentialAttributes.serializer(), encoded))

        val credential = PrincipalCredential(principal = Uuid.random(), attributes = attributes)
        assertIs<PasskeyCredentialAttributes>(credential.attributes)
        assertEquals(attributes, credential.attributes)
    }

    @Test
    fun `passkey supports identifier replacement but rejects passwords`() {
        val attributes = PasskeyCredentialAttributes(
            identifier = "old-id",
            name = "Laptop",
            publicKeyCose = "public-key",
            createdAt = "2026-08-04T12:00:00Z",
        )

        assertEquals("new-id", attributes.withIdentifier("new-id").identifier)
        assertFailsWith<UnsupportedOperationException> {
            attributes.withPassword(object : HashedEncodedPassword {
                override val hash = "hash"
            })
        }
    }
}
