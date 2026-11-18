package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogChoiceSearchTest {
    @Test
    fun `filtering precedes offset paging with exact end boundaries and disabled choices`() = runBlocking {
        val search = CatalogChoiceSearch()
        val first = search.search(CatalogChoiceQuery(" CONTROL ", 0))
        val middle = search.search(CatalogChoiceQuery("control", 3))
        val last = search.search(CatalogChoiceQuery("control", 6))
        assertNull(first.previousOffset)
        assertEquals(3, first.nextOffset)
        assertEquals(0, middle.previousOffset)
        assertEquals(6, middle.nextOffset)
        assertEquals(3, last.previousOffset)
        assertNull(last.nextOffset)
        assertTrue(first.options.single { it.value == "unavailable" }.disabled)
        val expected = CatalogChoiceSearch.options.filter { it.label.contains("control") }
        assertEquals(expected, first.options + middle.options + last.options)
        assertEquals(expected.size, expected.map { it.value }.distinct().size)
        val shortLast = search.search(CatalogChoiceQuery("layout", 3))
        assertEquals(listOf("page-heading", "section"), shortLast.options.map { it.value })
        assertNull(shortLast.nextOffset)
    }

    @Test
    fun `minimum query empty matches invalid offsets and requested failure stay distinct`() = runBlocking {
        val search = CatalogChoiceSearch()
        assertEquals(CatalogChoicePage(emptyList()), search.search(CatalogChoiceQuery("c", 0)))
        assertEquals(CatalogChoicePage(emptyList()), search.search(CatalogChoiceQuery("no-matches", 0)))
        assertFailsWith<IllegalArgumentException> { search.search(CatalogChoiceQuery("control", -1)) }
        assertFailsWith<IllegalStateException> { search.search(CatalogChoiceQuery("control", 0, fail = true)) }
        assertTrue(search.search(CatalogChoiceQuery("input", 0)).options.all { it.value.contains("input") })
    }

    @Test
    fun `contract round trips structured pages with absent navigation offsets`() = runBlocking {
        val response = CatalogChoiceSearch().dispatch(null, "search", """[{"query":"layout","offset":3}]""")
        val page = Json.decodeFromString(CatalogChoicePage.serializer(), response)
        assertEquals(0, page.previousOffset)
        assertNull(page.nextOffset)
        assertEquals(2, page.options.size)
        assertFalse("nextOffset" in response)
    }
}
