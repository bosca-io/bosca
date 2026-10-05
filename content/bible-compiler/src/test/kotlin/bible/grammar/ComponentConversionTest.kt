package bible.grammar

import bosca.bible.grammar.Compiler
import bosca.bible.usx.*
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the toComponent() conversion path, verifying that USX elements
 * are properly converted to the component model.
 */
class ComponentConversionTest {

    @Test
    fun testParagraphToComponent() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />In the beginning<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val component = para.toComponent(ComponentContext())
        assertNotNull(component)
        assertIs<ComponentContainer>(component)
        assertEquals(ContainerType.PARAGRAPH, component.type)
    }

    @Test
    fun testTableToComponent() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <table vid="1">
                    <row style="tr">
                        <cell style="tc1" align="start">Cell</cell>
                    </row>
                </table>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val table = chapter.items.filterIsInstance<Table>().first()

        val component = table.toComponent(ComponentContext())
        assertNotNull(component)
        assertIs<ComponentContainer>(component)
        assertEquals(ContainerType.TABLE, component.type)
    }

    @Test
    fun testRowToComponent() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <table vid="1">
                    <row style="tr">
                        <cell style="tc1" align="start">Cell</cell>
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

        val component = row.toComponent(ComponentContext())
        assertNotNull(component)
        assertIs<ComponentContainer>(component)
        assertEquals(ContainerType.ROW, component.type)
    }

    @Test
    fun testCellToComponent() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <table vid="1">
                    <row style="tr">
                        <cell style="tc1" align="start">Cell content</cell>
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

        val component = cell.toComponent(ComponentContext())
        assertNotNull(component)
        assertIs<ComponentContainer>(component)
        assertEquals(ContainerType.COLUMN, component.type)
    }

    @Test
    fun testVerseStartToComponent() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Text<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val verseStart = para.items.filterIsInstance<VerseStart>().first()

        val component = verseStart.toComponent(ComponentContext())
        assertNotNull(component)
        assertIs<bosca.bible.components.VerseStart>(component)
    }

    @Test
    fun testVerseEndToComponent() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Text<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val verseEnd = para.items.filterIsInstance<VerseEnd>().first()

        val component = verseEnd.toComponent(ComponentContext())
        assertNotNull(component)
        assertIs<bosca.bible.components.VerseEnd>(component)
    }

    @Test
    fun testTextToComponent() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Hello World<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val texts = para.items.filterIsInstance<Text>()
        assertTrue(texts.isNotEmpty())

        val component = texts.first().toComponent(ComponentContext())
        assertNotNull(component)
        assertIs<bosca.bible.components.Text>(component)
    }

    @Test
    fun testBreakToComponent() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Text<optbreak/>More<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val breaks = para.items.filterIsInstance<Break>()
        assertTrue(breaks.isNotEmpty())

        val component = breaks.first().toComponent(ComponentContext())
        assertNotNull(component)
        assertIs<bosca.bible.components.Break>(component)
    }

    @Test
    fun testMilestoneToComponentReturnsNull() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />
                    <ms style="qt-s" sid="GEN 1:1" eid=""/>Text<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val milestones = para.items.filterIsInstance<Milestone>()
        assertTrue(milestones.isNotEmpty())

        val component = milestones.first().toComponent(ComponentContext())
        assertEquals(null, component, "Milestone should return null component")
    }
}
