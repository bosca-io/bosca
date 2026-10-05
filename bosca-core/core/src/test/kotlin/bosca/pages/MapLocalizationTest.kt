package bosca.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapLocalizationTest {

    @Test
    fun `lookup returns the mapped value`() {
        val localization = MapLocalization(mapOf("site.name" to "Bosca"))
        assertEquals("Bosca", localization.lookup("site.name"))
    }

    @Test
    fun `lookup returns null for a missing key`() {
        val localization = MapLocalization(emptyMap())
        assertNull(localization.lookup("missing"))
    }

    @Test
    fun `localize returns the value as content`() {
        val localization = MapLocalization(mapOf("email.verify" to "Verify Email"))
        assertEquals("Verify Email", localization.localize("email.verify").toString())
    }

    @Test
    fun `localize falls back to the key when missing`() {
        val localization = MapLocalization(emptyMap())
        assertEquals("missing.key", localization.localize("missing.key").toString())
    }

    @Test
    fun `localize applies MessageFormat parameters`() {
        val localization = MapLocalization(mapOf("greeting" to "Hi {0}, welcome to {2}"))
        assertEquals(
            "Hi Ada, welcome to Bosca",
            localization.localize("greeting", "Ada", "ignored", "Bosca").toString()
        )
    }
}
