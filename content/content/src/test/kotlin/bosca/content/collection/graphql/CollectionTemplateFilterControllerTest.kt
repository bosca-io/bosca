package bosca.content.collection.graphql

import bosca.content.metadata.model.CollectionTemplateFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionTemplateFilterControllerTest {

    private val controller = CollectionTemplateFilterController()

    @Test
    fun `name returns filter name`() {
        val filter = CollectionTemplateFilter(name = "category", filter = "category.id = 'abc'")

        assertEquals("category", controller.name(filter))
    }

    @Test
    fun `filter returns filter expression`() {
        val filter = CollectionTemplateFilter(name = "category", filter = "category.id = 'abc'")

        assertEquals("category.id = 'abc'", controller.filter(filter))
    }
}
