package bosca.security.model

import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class PrincipalTest {

    @Test
    fun `principal equality copy defaults and hashes cover every constructor field`() {
        val created = OffsetDateTime.parse("2024-01-01T00:00:00Z")
        val modified = created.plusDays(1)
        val deleted = created.plusDays(2)
        val base = Principal(
            id = Uuid.random(),
            created = created,
            modified = modified,
            verified = true,
            anonymous = false,
            attributes = JsonPrimitive("attributes"),
            verificationToken = "verify",
            verificationOrigin = "https://example.com",
            primaryProfileId = Uuid.random(),
            tokenVersion = 7,
            deletedAt = deleted,
            hasLoginRevocations = true,
        )

        assertEquals(base, base)
        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
        assertEquals(base.id.toString(), base.toString())
        assertFalse(base.equals(null))
        assertFalse(base.equals("principal"))

        val variants = listOf(
            base.copy(id = Uuid.random()),
            base.copy(created = created.minusDays(1)),
            base.copy(modified = modified.plusDays(1)),
            base.copy(verified = false),
            base.copy(anonymous = true),
            base.copy(attributes = JsonPrimitive("different")),
            base.copy(verificationToken = "different"),
            base.copy(verificationOrigin = "https://other.example"),
            base.copy(primaryProfileId = Uuid.random()),
            base.copy(tokenVersion = 8),
            base.copy(deletedAt = null),
            base.copy(hasLoginRevocations = false),
        )
        variants.forEach { assertNotEquals(base, it) }

        val constructorMasks = listOf(
            Principal(id = base.id),
            Principal(created = created),
            Principal(modified = modified),
            Principal(verified = true),
            Principal(anonymous = false),
            Principal(attributes = JsonPrimitive("attributes")),
            Principal(verificationToken = "verify"),
            Principal(verificationOrigin = "https://example.com"),
            Principal(primaryProfileId = base.primaryProfileId),
            Principal(tokenVersion = 7),
            Principal(deletedAt = deleted),
            Principal(hasLoginRevocations = true),
            Principal(),
        )
        constructorMasks.forEach { it.hashCode() }
    }
}
