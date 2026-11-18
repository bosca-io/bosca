package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogContentTest {
    @Test
    fun `rejected comments retain exact drafts and do not affect another instance`() = runBlocking {
        val first = CatalogContent("first")
        val second = CatalogContent("second")
        first.comment("  x  ")
        assertEquals("  x  ", first.value)
        assertTrue(first.error.isNotEmpty())
        assertEquals(0, first.saves)
        first.comment("A complete comment")
        assertEquals("", first.error)
        assertEquals("A complete comment", first.saved)
        assertEquals(1, first.saves)
        assertEquals(CatalogContent("second"), second)
    }

    @Test
    fun `invalid JSON preserves draft and last saved value until corrected`() = runBlocking {
        val settings = CatalogJson("settings")
        settings.save("{\"enabled\":true}")
        settings.save("{ broken ")
        assertEquals("{ broken ", settings.text)
        assertTrue(settings.error.isNotEmpty())
        assertEquals(Json.parseToJsonElement("{\"enabled\":true}"), settings.saved)
        assertEquals(1, settings.saves)
        settings.save("[1,2,3]")
        assertEquals("", settings.error)
        assertEquals(Json.parseToJsonElement("[1,2,3]"), settings.saved)
        assertEquals(2, settings.saves)
    }
}
