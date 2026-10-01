package bosca.analytics.delivery

import bosca.core.platform.providers.Urls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoscaSinkConfigTest {
    @Test
    fun `app configuration selects the collector api and installed version`() {
        val config = BoscaSinkConfig(
            urls = urls("https://analytics.example.com/"),
            appId = "example-app",
        )

        assertEquals("https://analytics.example.com/api/v1", config.url)
        assertEquals("example-app", config.appId)
        assertEquals("mobile", config.clientId)
        assertTrue(config.appVersion.isNotBlank())
    }

    @Test
    fun `app configuration preserves an existing versioned api path`() {
        val config = BoscaSinkConfig(
            urls = urls("https://analytics.example.com/api/v1/"),
            appId = "reader",
            clientId = "desktop",
        )

        assertEquals("https://analytics.example.com/api/v1", config.url)
        assertEquals("desktop", config.clientId)
    }

    private fun urls(analytics: String) = Urls(
        web = "https://example.com",
        graphql = "https://example.com/graphql",
        graphqlWs = "wss://example.com/graphqlws",
        images = "https://example.com/content/image",
        api = "https://example.com",
        analytics = analytics,
    )
}
