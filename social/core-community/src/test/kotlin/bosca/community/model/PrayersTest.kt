package bosca.community.model

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrayersTest {

    private val now = OffsetDateTime.now()

    private fun samplePrayer() = Prayer(
        id = UUID.random(),
        profileId = UUID.random(),
        title = "Test prayer",
        content = JsonPrimitive("content"),
        status = PrayerStatus.ACTIVE,
        created = now,
        modified = now,
        lastActivityAt = now,
        attributes = null
    )

    @Test
    fun `empty page`() {
        val page = Prayers(prayers = emptyList(), total = 0)
        assertTrue(page.prayers.isEmpty())
        assertEquals(0L, page.total)
    }

    @Test
    fun `page with items`() {
        val prayers = listOf(samplePrayer(), samplePrayer())
        val page = Prayers(prayers = prayers, total = 5)
        assertEquals(2, page.prayers.size)
        assertEquals(5L, page.total)
    }

    @Test
    fun `data class equality`() {
        val prayers = listOf(samplePrayer())
        val a = Prayers(prayers = prayers, total = 1)
        val b = Prayers(prayers = prayers, total = 1)
        assertEquals(a, b)
    }
}
