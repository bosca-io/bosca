package bosca.analytics.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ServerEventContextTest {

    @Test
    fun `build uses explicit identity and server device values`() {
        val context = ServerEventContext.build(
            appId = "app-1",
            sessionId = "session-1",
            userId = "user-1",
        )

        assertEquals("app-1", context.appId)
        assertEquals("session-1", context.sessionId)
        assertEquals("user-1", context.userId)
        assertEquals("server", context.device.type)
        assertEquals("jvm", context.device.manufacturer)
        assertEquals("jvm", context.device.systemName)
        assertNotEquals("", context.device.installationId)
    }

    @Test
    fun `build supplies a stable process session by default`() {
        val first = ServerEventContext.build("app-1")
        val second = ServerEventContext.build("app-1")

        assertEquals(first.sessionId, second.sessionId)
    }

    @Test
    fun `build falls back when JVM environment properties are absent`() {
        val osName = System.getProperty("os.name")
        val javaVersion = System.getProperty("java.version")
        try {
            System.clearProperty("os.name")
            System.clearProperty("java.version")

            val context = ServerEventContext.build("app-1")

            assertEquals("unknown", context.device.platform)
            assertEquals("unknown", context.device.version)
        } finally {
            if (osName == null) System.clearProperty("os.name") else System.setProperty("os.name", osName)
            if (javaVersion == null) System.clearProperty("java.version") else System.setProperty("java.version", javaVersion)
        }
    }
}
