package bosca.analytics.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventContextTest {

    private val device = Device(
        installationId = "install-1",
        manufacturer = "Google",
        model = "Pixel 8",
        platform = "Android",
        primaryLocale = "en-US",
        systemName = "Android",
        timezone = "America/Los_Angeles",
        type = "phone",
        version = "14"
    )

    @Test
    fun `EventContext stores required fields`() {
        val context = EventContext(
            appId = "com.example.app",
            appVersion = "1.0.0",
            device = device,
            sessionId = "session-123"
        )
        assertEquals("com.example.app", context.appId)
        assertEquals("1.0.0", context.appVersion)
        assertEquals(device, context.device)
        assertEquals("session-123", context.sessionId)
    }

    @Test
    fun `EventContext has null defaults for optional fields`() {
        val context = EventContext(
            appId = "app",
            appVersion = "1.0",
            device = device,
            sessionId = "s1"
        )
        assertNull(context.browser)
        assertNull(context.geo)
        assertNull(context.userId)
    }

    @Test
    fun `EventContext with all fields`() {
        val browser = Browser(agent = "Chrome/120.0")
        val geo = Geo(city = "San Francisco", country = "US")
        val context = EventContext(
            appId = "com.example.app",
            appVersion = "2.0.0",
            browser = browser,
            device = device,
            geo = geo,
            sessionId = "session-456",
            userId = "user-789"
        )
        assertEquals(browser, context.browser)
        assertEquals(geo, context.geo)
        assertEquals("user-789", context.userId)
    }

    @Test
    fun `EventContext equality`() {
        val a = EventContext(appId = "a", appVersion = "1", device = device, sessionId = "s")
        val b = EventContext(appId = "a", appVersion = "1", device = device, sessionId = "s")
        assertEquals(a, b)
    }
}
