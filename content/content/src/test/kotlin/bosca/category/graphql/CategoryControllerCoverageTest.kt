package bosca.category.graphql

import bosca.category.model.Category
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryControllerCoverageTest {

    private val controller = CategoryController()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `id returns category id`() {
        val categoryId = UUID.random()
        val category = Category(id = categoryId, name = "News")

        assertEquals(categoryId, controller.id(category))
    }

    @Test
    fun `id returns NIL when category uses default id`() {
        val category = Category(name = "Sports")

        assertEquals(UUID.NIL, controller.id(category))
    }

    @Test
    fun `name returns category name`() {
        val category = Category(id = UUID.random(), name = "Technology")

        assertEquals("Technology", controller.name(category))
    }

    @Test
    fun `name returns empty string when category name is empty`() {
        val category = Category(id = UUID.random(), name = "")

        assertEquals("", controller.name(category))
    }
}
