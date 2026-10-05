package bosca.community.model

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrayerTest {

    private val now = OffsetDateTime.now()

    private fun samplePrayer(
        status: PrayerStatus = PrayerStatus.ACTIVE,
        attributes: kotlinx.serialization.json.JsonElement? = null
    ) = Prayer(
        id = UUID.random(),
        profileId = UUID.random(),
        title = "Sample prayer",
        content = JsonObject(mapOf("text" to JsonPrimitive("Lord, help me"))),
        status = status,
        created = now,
        modified = now,
        answeredAt = null,
        lastActivityAt = now,
        attributes = attributes
    )

    @Test
    fun `creation preserves all field values`() {
        val id = UUID.random()
        val profileId = UUID.random()
        val content = JsonObject(mapOf("text" to JsonPrimitive("Prayer text")))
        val prayer = Prayer(
            id = id,
            profileId = profileId,
            title = "Test prayer",
            content = content,
            status = PrayerStatus.ACTIVE,
            created = now,
            modified = now,
            answeredAt = null,
            lastActivityAt = now,
            attributes = null
        )
        assertEquals(id, prayer.id)
        assertEquals(profileId, prayer.profileId)
        assertEquals("Test prayer", prayer.title)
        assertEquals(content, prayer.content)
        assertEquals(PrayerStatus.ACTIVE, prayer.status)
        assertEquals(now, prayer.created)
        assertEquals(now, prayer.modified)
        assertNull(prayer.answeredAt)
        assertEquals(now, prayer.lastActivityAt)
        assertNull(prayer.attributes)
    }

    @Test
    fun `attributes can be set`() {
        val attrs = JsonObject(mapOf("priority" to JsonPrimitive("high")))
        val prayer = samplePrayer(attributes = attrs)
        assertEquals(attrs, prayer.attributes)
    }

    @Test
    fun `all PrayerStatus values are valid`() {
        val expected = setOf("ACTIVE", "CANCELLED", "ANSWERED", "PENDING", "BLOCKED", "PENDING_APPROVAL")
        val actual = PrayerStatus.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `each status can be used in Prayer`() {
        PrayerStatus.entries.forEach { status ->
            val prayer = samplePrayer(status = status)
            assertEquals(status, prayer.status)
        }
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val profileId = UUID.random()
        val content = JsonPrimitive("test")
        val a = Prayer(id = id, profileId = profileId, title = "test", content = content, status = PrayerStatus.ACTIVE, created = now, modified = now, lastActivityAt = now, attributes = null)
        val b = Prayer(id = id, profileId = profileId, title = "test", content = content, status = PrayerStatus.ACTIVE, created = now, modified = now, lastActivityAt = now, attributes = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality for different status`() {
        val id = UUID.random()
        val profileId = UUID.random()
        val content = JsonPrimitive("test")
        val a = Prayer(id = id, profileId = profileId, title = "test", content = content, status = PrayerStatus.ACTIVE, created = now, modified = now, lastActivityAt = now, attributes = null)
        val b = Prayer(id = id, profileId = profileId, title = "test", content = content, status = PrayerStatus.ANSWERED, created = now, modified = now, lastActivityAt = now, attributes = null)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy changes specific fields`() {
        val prayer = samplePrayer(status = PrayerStatus.PENDING)
        val updated = prayer.copy(status = PrayerStatus.ANSWERED)
        assertEquals(PrayerStatus.ANSWERED, updated.status)
        assertEquals(prayer.id, updated.id)
        assertEquals(prayer.profileId, updated.profileId)
    }

    @Test
    fun `toString contains field values`() {
        val prayer = samplePrayer()
        val str = prayer.toString()
        assertTrue(str.contains("ACTIVE"))
    }
}
