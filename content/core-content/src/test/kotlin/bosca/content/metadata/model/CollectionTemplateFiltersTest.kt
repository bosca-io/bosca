package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionTemplateFiltersTest {

    @Test
    fun `CollectionTemplateFilters stores filters`() {
        val filters = CollectionTemplateFilters(
            filters = listOf(
                CollectionTemplateFilter(filter = "type = 'article'", name = "Articles"),
                CollectionTemplateFilter(filter = "type = 'video'", name = "Videos")
            )
        )
        assertEquals(2, filters.filters.size)
        assertEquals("Articles", filters.filters[0].name)
        assertEquals("type = 'article'", filters.filters[0].filter)
    }

    @Test
    fun `CollectionTemplateFiltersInput stores filters`() {
        val input = CollectionTemplateFiltersInput(
            filters = listOf(
                CollectionTemplateFilterInput(filter = "status = 'active'", name = "Active")
            )
        )
        assertEquals(1, input.filters.size)
        assertEquals("Active", input.filters[0].name)
        assertEquals("status = 'active'", input.filters[0].filter)
    }
}
