package bosca.content.collection.graphql

import bosca.content.metadata.model.CollectionTemplateFilter
import bosca.content.metadata.model.CollectionTemplateFilters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionTemplateFiltersControllerTest {

    private val controller = CollectionTemplateFiltersController()

    @Test
    fun `filters returns all filters from the model`() {
        val filterList = listOf(
            CollectionTemplateFilter(name = "f1", filter = "expr1"),
            CollectionTemplateFilter(name = "f2", filter = "expr2")
        )
        val filtersModel = CollectionTemplateFilters(filters = filterList)

        val result = controller.filters(filtersModel)

        assertEquals(2, result.size)
        assertEquals("f1", result[0].name)
        assertEquals("f2", result[1].name)
    }

    @Test
    fun `filters returns empty list when no filters exist`() {
        val filtersModel = CollectionTemplateFilters(filters = emptyList())

        val result = controller.filters(filtersModel)

        assertTrue(result.isEmpty())
    }
}
