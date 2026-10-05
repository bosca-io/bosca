package bosca.content.transformations

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentToTextConfigurationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `default configuration has includeTitle true`() {
        val config = DocumentToTextConfiguration()
        assertTrue(config.includeTitle)
    }

    @Test
    fun `default configuration has includeTtsMarkup false`() {
        val config = DocumentToTextConfiguration()
        assertFalse(config.includeTtsMarkup)
    }

    @Test
    fun `default configuration has empty excludeContainers`() {
        val config = DocumentToTextConfiguration()
        assertTrue(config.excludeContainers.isEmpty())
    }

    @Test
    fun `custom configuration with all fields`() {
        val config = DocumentToTextConfiguration(
            includeTitle = false,
            includeTtsMarkup = true,
            excludeContainers = setOf("BIBLE_REFERENCES", "NOTES")
        )
        assertFalse(config.includeTitle)
        assertTrue(config.includeTtsMarkup)
        assertEquals(2, config.excludeContainers.size)
        assertTrue(config.excludeContainers.contains("BIBLE_REFERENCES"))
        assertTrue(config.excludeContainers.contains("NOTES"))
    }

    @Test
    fun `configuration serialization round-trip`() {
        val original = DocumentToTextConfiguration(
            includeTitle = false,
            includeTtsMarkup = true,
            excludeContainers = setOf("CONTAINER_A")
        )
        val serialized = json.encodeToString(DocumentToTextConfiguration.serializer(), original)
        val deserialized = json.decodeFromString(DocumentToTextConfiguration.serializer(), serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `configuration data class equality`() {
        val a = DocumentToTextConfiguration()
        val b = DocumentToTextConfiguration()
        assertEquals(a, b)
    }

    @Test
    fun `configuration copy updates includeTitle`() {
        val original = DocumentToTextConfiguration()
        val copy = original.copy(includeTitle = false)
        assertFalse(copy.includeTitle)
        assertFalse(copy.includeTtsMarkup)
    }
}
