package bosca.git.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeOwnersParserTest {

    @Test
    fun `parses basic CODEOWNERS entries`() {
        val content = """
            # This is a comment
            *.js @frontend-team
            /docs/ @docs-team @lead
        """.trimIndent()
        val entries = CodeOwnersParser.parse(content)
        assertEquals(2, entries.size)
        assertEquals("*.js", entries[0].pattern)
        assertEquals(listOf("@frontend-team"), entries[0].owners)
        assertEquals("/docs/", entries[1].pattern)
        assertEquals(listOf("@docs-team", "@lead"), entries[1].owners)
    }

    @Test
    fun `skips blank lines and comments`() {
        val content = """
            # comment

            *.kt @kotlin-team

            # another comment
        """.trimIndent()
        val entries = CodeOwnersParser.parse(content)
        assertEquals(1, entries.size)
    }

    @Test
    fun `findOwners uses last-match-wins`() {
        val entries = CodeOwnersParser.parse("""
            ** @default-team
            *.kt @kotlin-team
            src/main/*.kt @core-team
        """.trimIndent())

        assertEquals(listOf("@core-team"), CodeOwnersParser.findOwners(entries, "src/main/App.kt"))
        assertEquals(listOf("@kotlin-team"), CodeOwnersParser.findOwners(entries, "Foo.kt"))
        assertEquals(listOf("@default-team"), CodeOwnersParser.findOwners(entries, "test/Foo.kt"))
        assertEquals(listOf("@default-team"), CodeOwnersParser.findOwners(entries, "README.md"))
    }

    @Test
    fun `empty content returns no entries`() {
        assertTrue(CodeOwnersParser.parse("").isEmpty())
    }

    @Test
    fun `lines with only pattern and no owners are skipped`() {
        val entries = CodeOwnersParser.parse("*.txt")
        assertTrue(entries.isEmpty())
    }
}
