package bosca.server

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    @Test
    fun `RunnerConfig defaults to empty enabled list`() {
        val config = RunnerConfig()
        assertEquals(emptyList(), config.enabled)
    }

    @Test
    fun `RunnerConfig serializes and deserializes correctly`() {
        val json = Json
        val config = RunnerConfig(enabled = listOf("default", "content"))
        val serialized = json.encodeToString(RunnerConfig.serializer(), config)
        val deserialized = json.decodeFromString(RunnerConfig.serializer(), serialized)

        assertEquals(config, deserialized)
        assertEquals(listOf("default", "content"), deserialized.enabled)
    }

    @Test
    fun `RunnerConfig deserializes from JSON`() {
        val json = Json
        val input = """{"enabled":["queue1","queue2"]}"""
        val config = json.decodeFromString(RunnerConfig.serializer(), input)

        assertEquals(listOf("queue1", "queue2"), config.enabled)
    }

    @Test
    fun `application module composes communications routes`() {
        val bytecode = checkNotNull(
            javaClass.classLoader.getResourceAsStream("bosca/server/ApplicationKt.class"),
        ).use { it.readBytes() }

        assertTrue(
            bytecode.toString(Charsets.ISO_8859_1).contains("configureCommunicationsRoutes"),
            "Bosca server application must invoke the generated communications route registrar",
        )
    }
}
