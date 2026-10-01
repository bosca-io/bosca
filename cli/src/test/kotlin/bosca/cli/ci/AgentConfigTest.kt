package bosca.cli.ci

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentConfigTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `serializes all fields`() {
        val config = AgentConfig(
            agentId = "550e8400-e29b-41d4-a716-446655440000",
            token = "bga_test_token",
            serverUrl = "https://git.bosca.io",
            name = "agent-01",
            labels = listOf("linux", "default"),
            mode = "runner",
            pollIntervalSeconds = 10,
            workDir = "/tmp/ci",
            providerApiToken = "dop_v1_test",
            selfDestructToken = "dop_v1_sd",
        )

        val serialized = json.encodeToString(config)
        val deserialized = json.decodeFromString<AgentConfig>(serialized)

        assertEquals(config.agentId, deserialized.agentId)
        assertEquals(config.token, deserialized.token)
        assertEquals(config.serverUrl, deserialized.serverUrl)
        assertEquals(config.name, deserialized.name)
        assertEquals(config.labels, deserialized.labels)
        assertEquals(config.mode, deserialized.mode)
        assertEquals(config.pollIntervalSeconds, deserialized.pollIntervalSeconds)
        assertEquals(config.workDir, deserialized.workDir)
        assertEquals(config.providerApiToken, deserialized.providerApiToken)
        assertEquals(config.selfDestructToken, deserialized.selfDestructToken)
    }

    @Test
    fun `deserializes with defaults for missing optional fields`() {
        val minimal = """
            {
                "agentId": "test-id",
                "token": "test-token",
                "serverUrl": "https://example.com"
            }
        """.trimIndent()

        val config = json.decodeFromString<AgentConfig>(minimal)
        assertEquals("test-id", config.agentId)
        assertEquals("test-token", config.token)
        assertEquals("https://example.com", config.serverUrl)
        assertEquals("", config.name)
        assertEquals(emptyList(), config.labels)
        assertEquals("runner", config.mode)
        assertEquals(5, config.pollIntervalSeconds)
        assertEquals("/tmp/bosca-ci", config.workDir)
        assertNull(config.providerApiToken)
        assertNull(config.selfDestructToken)
    }

    @Test
    fun `ignores unknown fields during deserialization`() {
        val withExtras = """
            {
                "agentId": "test-id",
                "token": "test-token",
                "serverUrl": "https://example.com",
                "futureField": "some-value",
                "anotherNewField": 42
            }
        """.trimIndent()

        val config = json.decodeFromString<AgentConfig>(withExtras)
        assertNotNull(config)
        assertEquals("test-id", config.agentId)
    }
}
