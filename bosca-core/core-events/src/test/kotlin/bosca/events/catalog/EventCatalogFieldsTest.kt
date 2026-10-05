package bosca.events.catalog

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class EventCatalogFieldsTest {

    @Serializable
    private data class Sample(
        val name: String,
        val count: Long,
        val active: Boolean,
        val ratio: Double,
        val tags: List<String>,
        val note: String?,
    )

    @Test
    fun `extracts the name and coarse type label of every top-level field`() {
        val fields = EventCatalogFields.of(Sample.serializer().descriptor)
        assertEquals(
            listOf(
                EventField("name", "String"),
                EventField("count", "Long"),
                EventField("active", "Boolean"),
                EventField("ratio", "Double"),
                EventField("tags", "List"),
                EventField("note", "String?"),
            ),
            fields,
        )
    }
}
