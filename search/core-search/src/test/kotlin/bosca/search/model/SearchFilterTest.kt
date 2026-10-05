package bosca.search.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SearchFilterTest {

    @Test
    fun `Eq produces field = value filter`() {
        val filter = SearchFilter.eq("contentId", "abc-123")
        assertEquals("contentId = \"abc-123\"", filter.toFilterString())
    }

    @Test
    fun `Eq escapes double quotes in value`() {
        val filter = SearchFilter.eq("field", """value with "quotes" inside""")
        assertEquals("""field = "value with \"quotes\" inside"""", filter.toFilterString())
    }

    @Test
    fun `Eq escapes backslashes in value`() {
        val filter = SearchFilter.eq("field", """path\to\file""")
        assertEquals("""field = "path\\to\\file"""", filter.toFilterString())
    }

    @Test
    fun `Eq escapes backslash before quote`() {
        val filter = SearchFilter.eq("field", """test\"injected""")
        assertEquals("""field = "test\\\"injected"""", filter.toFilterString())
    }

    @Test
    fun `And joins conditions with AND`() {
        val filter = SearchFilter.and(
            SearchFilter.eq("contentId", "abc-123"),
            SearchFilter.eq("languageTag", "en"),
        )
        assertEquals("""contentId = "abc-123" AND languageTag = "en"""", filter.toFilterString())
    }

    @Test
    fun `And with single condition produces no AND keyword`() {
        val filter = SearchFilter.and(SearchFilter.eq("field", "value"))
        assertEquals("""field = "value"""", filter.toFilterString())
    }

    @Test
    fun `And escapes values in all conditions`() {
        val filter = SearchFilter.and(
            SearchFilter.eq("contentId", "abc-123"),
            SearchFilter.eq("languageTag", """en" OR 1=1 --"""),
        )
        assertEquals("""contentId = "abc-123" AND languageTag = "en\" OR 1=1 --"""", filter.toFilterString())
    }

    @Test
    fun `Eq allows dotted field names for nested access`() {
        val filter = SearchFilter.eq("topics.id", "abc-123")
        assertEquals("""topics.id = "abc-123"""", filter.toFilterString())
    }

    @Test
    fun `Eq rejects field names with special characters`() {
        assertFailsWith<IllegalArgumentException> {
            SearchFilter.eq("""field" OR 1=1 --""", "value")
        }
    }

    @Test
    fun `Eq rejects field names with spaces`() {
        assertFailsWith<IllegalArgumentException> {
            SearchFilter.eq("field name", "value")
        }
    }
}
