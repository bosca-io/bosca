package bosca.messages.pages.localization

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvReaderTest {

    @Test
    fun `parses simple rows`() {
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("1", "2", "3")),
            CsvReader.parse("a,b,c\n1,2,3\n")
        )
    }

    @Test
    fun `parses a quoted field containing a comma`() {
        assertEquals(
            listOf(listOf("k", "en", "Hello, world")),
            CsvReader.parse("\"k\",\"en\",\"Hello, world\"\n")
        )
    }

    @Test
    fun `unescapes doubled quotes`() {
        assertEquals(
            listOf(listOf("k", "fr", "L''équipe \"X\"")),
            CsvReader.parse("\"k\",\"fr\",\"L''équipe \"\"X\"\"\"\n")
        )
    }

    @Test
    fun `keeps newlines inside quoted fields`() {
        assertEquals(
            listOf(listOf("k", "en", "line1\nline2")),
            CsvReader.parse("\"k\",\"en\",\"line1\nline2\"\n")
        )
    }

    @Test
    fun `preserves MessageFormat placeholders verbatim`() {
        assertEquals(
            listOf(listOf("greeting", "en", "Hi {0}, welcome to {2}")),
            CsvReader.parse("\"greeting\",\"en\",\"Hi {0}, welcome to {2}\"\n")
        )
    }

    @Test
    fun `handles CRLF endings and a missing final newline`() {
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d")),
            CsvReader.parse("a,b\r\nc,d")
        )
    }

    @Test
    fun `keeps a trailing empty field`() {
        assertEquals(
            listOf(listOf("a", "")),
            CsvReader.parse("a,\n")
        )
    }
}
