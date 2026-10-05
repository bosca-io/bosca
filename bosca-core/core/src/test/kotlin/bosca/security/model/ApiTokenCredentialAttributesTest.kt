package bosca.security.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ApiTokenCredentialAttributesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `type returns API_TOKEN`() {
        val attrs = createAttributes()
        assertEquals(CredentialType.API_TOKEN, attrs.type)
    }

    @Test
    fun `withIdentifier returns copy with new identifier`() {
        val attrs = createAttributes(identifier = "sha256:abc")
        val updated = attrs.withIdentifier("sha256:def")
        assertEquals("sha256:def", updated.identifier)
        assertEquals(attrs.name, (updated as ApiTokenCredentialAttributes).name)
    }

    @Test
    fun `withPassword throws UnsupportedOperationException`() {
        val attrs = createAttributes()
        assertFailsWith<UnsupportedOperationException> {
            attrs.withPassword(object : HashedEncodedPassword {
                override val hash = "test"
            })
        }
    }

    @Test
    fun `serialization round-trip preserves all fields`() {
        val attrs = createAttributes(
            scopes = listOf("content:view", "storage:read"),
            allowedGroups = listOf("group-1", "group-2"),
            expiresAt = "2027-01-15T00:00:00Z",
            description = "Test token",
        )

        val jsonStr = json.encodeToString(ApiTokenCredentialAttributes.serializer(), attrs)
        val deserialized = json.decodeFromString(ApiTokenCredentialAttributes.serializer(), jsonStr)

        assertEquals(attrs.identifier, deserialized.identifier)
        assertEquals(attrs.name, deserialized.name)
        assertEquals(attrs.description, deserialized.description)
        assertEquals(attrs.tokenPrefix, deserialized.tokenPrefix)
        assertEquals(attrs.scopes, deserialized.scopes)
        assertEquals(attrs.allowedGroups, deserialized.allowedGroups)
        assertEquals(attrs.expiresAt, deserialized.expiresAt)
        assertEquals(attrs.createdBy, deserialized.createdBy)
    }

    @Test
    fun `serialization handles null optional fields`() {
        val attrs = createAttributes(
            scopes = null,
            allowedGroups = null,
            expiresAt = null,
            description = null,
        )

        val jsonStr = json.encodeToString(ApiTokenCredentialAttributes.serializer(), attrs)
        val deserialized = json.decodeFromString(ApiTokenCredentialAttributes.serializer(), jsonStr)

        assertNull(deserialized.scopes)
        assertNull(deserialized.allowedGroups)
        assertNull(deserialized.expiresAt)
        assertNull(deserialized.description)
    }

    @Test
    fun `PrincipalCredential round-trip with API_TOKEN type`() {
        val attrs = createAttributes()
        val credential = PrincipalCredential(
            principal = bosca.serialization.UUID.random(),
            attributes = attrs,
        )

        assertEquals(CredentialType.API_TOKEN, credential.type)
        val restored = credential.attributes
        assertEquals(attrs.identifier, restored.identifier)
        assertEquals(CredentialType.API_TOKEN, restored.type)

        val tokenAttrs = restored as ApiTokenCredentialAttributes
        assertEquals(attrs.name, tokenAttrs.name)
        assertEquals(attrs.tokenPrefix, tokenAttrs.tokenPrefix)
    }

    private fun createAttributes(
        identifier: String = "sha256:abc123def456",
        name: String = "CI Token",
        description: String? = "For CI/CD",
        tokenPrefix: String = "bsk_a1b2c3d4",
        scopes: List<String>? = listOf("content:view"),
        allowedGroups: List<String>? = null,
        expiresAt: String? = null,
        createdBy: String = "00000000-0000-0000-0000-000000000001",
    ) = ApiTokenCredentialAttributes(
        identifier = identifier,
        name = name,
        description = description,
        tokenPrefix = tokenPrefix,
        scopes = scopes,
        allowedGroups = allowedGroups,
        expiresAt = expiresAt,
        createdBy = createdBy,
    )
}
