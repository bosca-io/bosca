package bible.grammar

import bosca.bible.grammar.Compiler
import bosca.bible.usx.*
import bosca.bible.usx.List as UsxList
import bosca.bible.usx.Char as UsxChar
import bosca.bible.ListStyle
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ListVariationsTest {

    @Test
    fun testNestedLists() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="li1">Level 1 Item 1</para>
                <para style="li2">Level 2 Item 1</para>
                <para style="li1">Level 1 Item 2</para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val root = usx
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val items = chapter.items

        assertEquals(3, items.size)
        assertIs<UsxList>(items[0])
        assertIs<UsxList>(items[1])
        assertIs<UsxList>(items[2])

        assertEquals(ListStyle.li1, (items[0] as UsxList).style)
        assertEquals(ListStyle.li2, (items[1] as UsxList).style)
        assertEquals(ListStyle.li1, (items[2] as UsxList).style)
    }

    @Test
    fun testOtherListStyles() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="lh">List Header</para>
                <para style="lim1">Embedded List Item</para>
                <para style="lf">List Footer</para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        val chapter = (usx as Root).items.filterIsInstance<Chapter>().first()
        val items = chapter.items

        assertEquals(3, items.size)
        assertEquals(ListStyle.lh, (items[0] as UsxList).style)
        assertEquals(ListStyle.lim1, (items[1] as UsxList).style)
        assertEquals(ListStyle.lf, (items[2] as UsxList).style)
    }

    @Test
    fun testComplexContent() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="li">
                    Text
                    <note style="f" caller="+"><char style="fr">1.1: </char><char style="ft">Note</char></note>
                    <char style="wj">Words of Jesus</char>
                    <optbreak/>
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        val chapter = (usx as Root).items.filterIsInstance<Chapter>().first()
        val list = chapter.items.first() as UsxList

        // Check content
        // Text, Footnote, Char, Break (Text might be split by items)
        // Implementation detail: Text nodes are usually separate items in the container.
        
        // Items in list: Text("Text\n        "), Footnote, Char, Break, Text("\n    ")
        // Exact count depends on whitespace handling.
        
        val footnote = list.items.find { it is Footnote }
        val charItem = list.items.find { it is UsxChar }
        
        println("List items: " + list.items.map { it.javaClass.simpleName })
        
        // optbreak maps to Break
        // Wait, Break is not in the imports I added, but I used wildcards. 
        // Let's check imports. Break is in bosca.bible.usx
        val breakItem = list.items.find { it is bosca.bible.usx.Break }

        assertTrue(footnote != null, "Should contain Footnote")
        assertTrue(charItem != null, "Should contain Char")
        assertTrue(breakItem != null, "Should contain Break")
    }

    @Test
    fun testSequentialLists() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="li">List 1</para>
                <para style="p">Paragraph</para>
                <para style="li">List 2</para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        val chapter = (usx as Root).items.filterIsInstance<Chapter>().first()
        val items = chapter.items

        assertEquals(3, items.size)
        assertIs<UsxList>(items[0])
        assertIs<Paragraph>(items[1])
        assertIs<UsxList>(items[2])
    }

    @Test
    fun testEmptyListItem() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="li"/>
                <para style="p">Next</para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        val chapter = (usx as Root).items.filterIsInstance<Chapter>().first()
        val items = chapter.items

        assertEquals(2, items.size)
        assertIs<UsxList>(items[0])
        assertIs<Paragraph>(items[1])
        
        assertEquals(0, (items[0] as UsxList).items.size)
    }
}
