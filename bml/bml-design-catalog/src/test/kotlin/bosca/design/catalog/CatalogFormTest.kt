package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CatalogFormTest {
    @Test
    fun `rejected names retain the exact draft without recording a save`() = runBlocking {
        val form = CatalogForm("example")
        form.save(" a ")
        assertEquals(" a ", form.displayName)
        assertEquals("Use at least three characters.", form.error)
        assertEquals("", form.message)
        assertEquals(0, form.saves)
    }

    @Test
    fun `instances keep independent values and recover from validation errors`() = runBlocking {
        val first = CatalogForm("first")
        val second = CatalogForm("second")
        first.save("x")
        first.save("Fresh sample")
        first.toggleUpdates()
        assertEquals("Saved Fresh sample.", first.message)
        assertEquals("", first.error)
        assertEquals(1, first.saves)
        assertEquals(0, second.saves)
        assertEquals("Design library", second.displayName)
        assertFalse(second.updates)
    }
}
