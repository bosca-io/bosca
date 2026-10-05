package bosca.ide.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoscaServerProfileTest {
    @Test
    fun `normalization mints an id and derives the WebSocket endpoint`() {
        val profile = BoscaServerProfile(
            name = "  Production  ",
            graphqlEndpoint = "https://bosca.example/graphql/",
            cliProfileName = "  production  ",
        ).normalized()

        assertTrue(profile.id.isNotBlank())
        assertEquals("Production", profile.name)
        assertEquals("https://bosca.example/graphql", profile.graphqlEndpoint)
        assertEquals("wss://bosca.example/ws", profile.webSocketEndpoint)
        assertEquals("production", profile.cliProfileName)
    }

    @Test
    fun `normalization preserves an explicit secure WebSocket endpoint`() {
        val profile = BoscaServerProfile(
            id = "server-a",
            name = "Production",
            graphqlEndpoint = "https://bosca.example/graphql",
            webSocketEndpoint = "wss://events.bosca.example/graphql",
        ).normalized()

        assertEquals("server-a", profile.id)
        assertEquals("wss://events.bosca.example/graphql", profile.webSocketEndpoint)
    }

    @Test
    fun `endpoints reject embedded credentials and unsupported schemes`() {
        assertThrows(IllegalArgumentException::class.java) {
            BoscaServerProfile(
                name = "Unsafe",
                graphqlEndpoint = "https://token@bosca.example/graphql",
            ).normalized()
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoscaServerProfile(
                name = "Unsafe",
                graphqlEndpoint = "file:///tmp/graphql",
            ).normalized()
        }
    }

    @Test
    fun `entity identity includes the originating server`() {
        val first = BoscaEntityKey("server-a", "same-uuid")
        val second = BoscaEntityKey("server-b", "same-uuid")

        assertNotEquals(first, second)
        assertEquals(2, setOf(first, second).size)
    }
}
