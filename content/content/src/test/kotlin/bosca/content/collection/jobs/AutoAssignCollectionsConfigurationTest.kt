package bosca.content.collection.jobs

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoAssignCollectionsConfigurationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `AttributeValue creation with all fields`() {
        val av = AttributeValue(key = "topic", value = "science", slug = "science-collection")
        assertEquals("topic", av.key)
        assertEquals("science", av.value)
        assertEquals("science-collection", av.slug)
    }

    @Test
    fun `AttributeValue data class equality`() {
        val a = AttributeValue(key = "k", value = "v", slug = "s")
        val b = AttributeValue(key = "k", value = "v", slug = "s")
        assertEquals(a, b)
    }

    @Test
    fun `AutoAssignCollectionsConfiguration creation with attributes list`() {
        val attrs = listOf(
            AttributeValue(key = "category", value = "news", slug = "news-collection"),
            AttributeValue(key = "type", value = "article", slug = "articles")
        )
        val config = AutoAssignCollectionsConfiguration(attributes = attrs)
        assertEquals(2, config.attributes.size)
        assertEquals("category", config.attributes[0].key)
        assertEquals("articles", config.attributes[1].slug)
    }

    @Test
    fun `AutoAssignCollectionsConfiguration with empty attributes list`() {
        val config = AutoAssignCollectionsConfiguration(attributes = emptyList())
        assertTrue(config.attributes.isEmpty())
    }

    @Test
    fun `AutoAssignCollectionsConfiguration serialization round-trip`() {
        val original = AutoAssignCollectionsConfiguration(
            attributes = listOf(
                AttributeValue(key = "k1", value = "v1", slug = "s1"),
                AttributeValue(key = "k2", value = "v2", slug = "s2")
            )
        )
        val serialized = json.encodeToString(AutoAssignCollectionsConfiguration.serializer(), original)
        val deserialized = json.decodeFromString(AutoAssignCollectionsConfiguration.serializer(), serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `AttributeValue serialization round-trip`() {
        val original = AttributeValue(key = "a", value = "b", slug = "c")
        val serialized = json.encodeToString(AttributeValue.serializer(), original)
        val deserialized = json.decodeFromString(AttributeValue.serializer(), serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `AutoAssignCollectionsConfiguration data class equality`() {
        val attrs = listOf(AttributeValue(key = "k", value = "v", slug = "s"))
        val a = AutoAssignCollectionsConfiguration(attributes = attrs)
        val b = AutoAssignCollectionsConfiguration(attributes = attrs)
        assertEquals(a, b)
    }
}
