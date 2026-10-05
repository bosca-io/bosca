package bosca.analytics.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPlatformTest {
    @Test
    fun `desktop platform supplies device values and microseconds`() {
        val device = currentDevice("iid")
        assertEquals("iid", device.installationId)
        assertEquals("desktop", device.type)
        assertTrue(currentMicros() in 0..999)
    }

    @Test
    fun `desktop device falls back when optional system properties are absent`() {
        val properties = listOf("java.vendor", "os.arch", "os.name", "os.version")
        val originals = properties.associateWith(System::getProperty)
        try {
            properties.forEach(System::clearProperty)
            val device = currentDevice("iid")
            assertEquals("Unknown", device.manufacturer)
            assertEquals("Unknown", device.model)
            assertEquals("DESKTOP", device.platform)
            assertEquals("Unknown", device.version)
        } finally {
            originals.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    @Test
    fun `desktop app version reads the launcher property and falls back for local runs`() {
        val original = System.getProperty(APP_VERSION_PROPERTY)
        try {
            System.setProperty(APP_VERSION_PROPERTY, "6.12.5")
            assertEquals("6.12.5", currentAppVersion())
            System.clearProperty(APP_VERSION_PROPERTY)
            assertEquals("dev", currentAppVersion())
        } finally {
            if (original == null) System.clearProperty(APP_VERSION_PROPERTY)
            else System.setProperty(APP_VERSION_PROPERTY, original)
        }
    }
}
