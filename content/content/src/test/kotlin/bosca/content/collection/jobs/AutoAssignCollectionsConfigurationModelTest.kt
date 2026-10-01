package bosca.content.collection.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoAssignCollectionsConfigurationModelTest {

    @Test
    fun `AttributeValue field preservation`() {
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
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `AttributeValue copy changes slug`() {
        val av = AttributeValue(key = "k", value = "v", slug = "old")
        val modified = av.copy(slug = "new")
        assertEquals("new", modified.slug)
        assertEquals("k", modified.key)
    }

    @Test
    fun `AutoAssignCollectionsConfiguration preserves attributes list`() {
        val attrs = listOf(
            AttributeValue(key = "topic", value = "science", slug = "science"),
            AttributeValue(key = "level", value = "beginner", slug = "beginner")
        )
        val config = AutoAssignCollectionsConfiguration(attributes = attrs)
        assertEquals(2, config.attributes.size)
        assertEquals("topic", config.attributes[0].key)
        assertEquals("level", config.attributes[1].key)
    }

    @Test
    fun `AutoAssignCollectionsConfiguration with empty list`() {
        val config = AutoAssignCollectionsConfiguration(attributes = emptyList())
        assertTrue(config.attributes.isEmpty())
    }

    @Test
    fun `AutoAssignCollectionsConfiguration data class equality`() {
        val attrs = listOf(AttributeValue(key = "k", value = "v", slug = "s"))
        val a = AutoAssignCollectionsConfiguration(attributes = attrs)
        val b = AutoAssignCollectionsConfiguration(attributes = attrs)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
