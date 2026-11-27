package bosca.server.netty

import bosca.server.config.ApplicationConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NettyServerSettingsTest {
    @Test
    fun `management listener accepts both port boundaries and disables blank malformed or out of range ports`() {
        for (value in listOf("", "  ", "-1", "65536", "invalid")) {
            assertNull(NettyServerSettings.configuredManagementPort(config("management-port", value)), value)
        }
        assertEquals(0, NettyServerSettings.configuredManagementPort(config("management-port", " 0 ")))
        assertEquals(65535, NettyServerSettings.configuredManagementPort(config("management-port", "65535")))
        assertNull(NettyServerSettings.configuredManagementPort(ApplicationConfig.load("".byteInputStream())))
    }

    @Test
    fun `request dispatch configuration normalizes known values and falls back for empty or unknown choices`() {
        for (value in listOf("POOL", " pool ")) {
            assertEquals(RequestDispatcherMode.POOL,
                NettyServerSettings.configuredRequestDispatcher(config("request-dispatcher", value)))
        }
        for (value in listOf("EVENT-LOOP", " event-loop ", "", "unknown")) {
            assertEquals(RequestDispatcherMode.EVENT_LOOP,
                NettyServerSettings.configuredRequestDispatcher(config("request-dispatcher", value)))
        }
    }

    private fun config(key: String, value: String) = ApplicationConfig.load(
        "bosca:\n  server:\n    $key: ${JsonPrimitive(value)}\n".byteInputStream(),
    )
}
