package bible.grammar

import bosca.bible.grammar.Compiler
import bosca.bible.usx.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for table parsing, including rows, cells, cell alignment,
 * and content within table cells.
 */
class TableTest {

    @Test
    fun testBasicTable() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <table vid="1">
                    <row style="tr">
                        <cell style="tc1" align="start">Name</cell>
                        <cell style="tc2" align="end">Value</cell>
                    </row>
                </table>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val table = chapter.items.filterIsInstance<Table>().firstOrNull()

        assertNotNull(table)
        assertEquals(1, table.items.size)

        val row = table.items[0]
        assertIs<Row>(row)
        assertEquals("tr", row.style)
    }

    @Test
    fun testTableMultipleRows() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <table vid="1">
                    <row style="tr">
                        <cell style="tc1" align="start">Row 1 Col 1</cell>
                        <cell style="tc2" align="end">Row 1 Col 2</cell>
                    </row>
                    <row style="tr">
                        <cell style="tc1" align="start">Row 2 Col 1</cell>
                        <cell style="tc2" align="end">Row 2 Col 2</cell>
                    </row>
                    <row style="tr">
                        <cell style="tc1" align="start">Row 3 Col 1</cell>
                        <cell style="tc2" align="end">Row 3 Col 2</cell>
                    </row>
                </table>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val table = chapter.items.filterIsInstance<Table>().first()

        assertEquals(3, table.items.size)
    }

    @Test
    fun testTableCellAttributes() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <table vid="1">
                    <row style="tr">
                        <cell style="tc1" align="start">Cell</cell>
                        <cell style="tc2" align="end">Cell</cell>
                    </row>
                </table>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val table = chapter.items.filterIsInstance<Table>().first()
        val row = table.items[0]
        val cells = row.items.filterIsInstance<TableContent>()

        assertEquals(2, cells.size)
        assertEquals("tc1", cells[0].style)
        assertEquals("start", cells[0].align)
        assertEquals("tc2", cells[1].style)
        assertEquals("end", cells[1].align)
    }

    @Test
    fun testTableCellWithVerses() {
        val usxXml = """
            <usx version="3.0">
                <book code="NUM" style="id">Numbers</book>
                <para style="h">Numbers</para>
                <para style="mt1">Numbers</para>
                <chapter number="1" style="c" sid="NUM 1" />
                <table vid="1">
                    <row style="tr">
                        <cell style="tc1" align="start">
                            <verse number="1" style="v" sid="NUM 1:1" />First verse in table<verse eid="NUM 1:1" />
                        </cell>
                    </row>
                </table>
                <chapter eid="NUM 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val table = chapter.items.filterIsInstance<Table>().first()
        val row = table.items[0]
        val cell = row.items.filterIsInstance<TableContent>().first()
        val verseStarts = cell.items.filterIsInstance<VerseStart>()
        assertTrue(verseStarts.isNotEmpty(), "Table cell should contain verse starts")
    }

    @Test
    fun testTableCellTextContent() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <table vid="1">
                    <row style="tr">
                        <cell style="tc1" align="start">Hello World</cell>
                    </row>
                </table>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val table = chapter.items.filterIsInstance<Table>().first()
        val row = table.items[0]
        val cell = row.items.filterIsInstance<TableContent>().first()
        assertTrue(cell.toString(StringContext.default).contains("Hello World"))
    }

    @Test
    fun testMultipleTablesInChapter() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Text<verse eid="GEN 1:1" />
                </para>
                <table vid="1">
                    <row style="tr">
                        <cell style="tc1" align="start">Table 1</cell>
                    </row>
                </table>
                <para style="p">
                    <verse number="2" style="v" sid="GEN 1:2" />More text<verse eid="GEN 1:2" />
                </para>
                <table vid="2">
                    <row style="tr">
                        <cell style="tc1" align="start">Table 2</cell>
                    </row>
                </table>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val tables = chapter.items.filterIsInstance<Table>()
        assertEquals(2, tables.size)
    }
}
