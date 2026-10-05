package bible.grammar

import bosca.bible.grammar.Compiler
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import bosca.bible.usx.Root
import bosca.bible.usx.List
import bosca.bible.usx.Paragraph

class ListSupportTest {

    @Test
    fun testCompileList() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Introduction<verse eid="GEN 1:1" />
                </para>
                <para style="li">Item 1</para>
                <para style="li">Item <char style="wj">2</char></para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)

        assertIs<Root>(usx)
        val root = usx

        // Find the list items. They should be inside the Chapter.
        val chapter = root.items.filterIsInstance<bosca.bible.usx.Chapter>().first()
        val listItems = chapter.items.filterIsInstance<List>()
        val paraItems = chapter.items.filterIsInstance<Paragraph>()

        assertTrue(listItems.isNotEmpty(), "Should find List items")
        assertEquals(2, listItems.size, "Should find 2 List items")

        val secondList = listItems[1]
        val charItem = secondList.items.find { it is bosca.bible.usx.Char }
        assertTrue(charItem != null, "Should find Char in second List item")
    }

    @Test
    fun testListPop() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="li">Item 1</para>
                <para style="p">After List</para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)

        assertIs<Root>(usx)
        val root = usx
        val chapter = root.items.filterIsInstance<bosca.bible.usx.Chapter>().first()

        // We expect: List, Paragraph.
        // If List not popped, Paragraph would be inside List.
        val items = chapter.items
        assertEquals(2, items.size, "Chapter should have 2 items (List, Para)")
        assertIs<bosca.bible.usx.List>(items[0])
        assertIs<Paragraph>(items[1])
    }
}
