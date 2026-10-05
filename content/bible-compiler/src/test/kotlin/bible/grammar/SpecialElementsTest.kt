package bible.grammar

import bosca.bible.grammar.Compiler
import bosca.bible.usx.*
import bosca.bible.usx.Char as UsxChar
import bosca.bible.usx.Reference as UsxReference
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for special USX elements: sidebars, figures, milestones,
 * breaks, and reference elements.
 */
class SpecialElementsTest {

    @Test
    fun testSidebar() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Text<verse eid="GEN 1:1" />
                </para>
                <sidebar style="esb" category="History">
                    <para style="ms">Historical Note</para>
                    <para style="p">Sidebar content here.</para>
                </sidebar>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val sidebars = chapter.items.filterIsInstance<Sidebar>()
        assertEquals(1, sidebars.size)
        assertEquals("esb", sidebars[0].style)
        assertEquals("History", sidebars[0].category)
    }

    @Test
    fun testSidebarWithParagraphs() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <sidebar style="esb">
                    <para style="ms">Title</para>
                    <para style="p">Para 1</para>
                    <para style="p">Para 2</para>
                </sidebar>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val sidebar = chapter.items.filterIsInstance<Sidebar>().first()
        val paragraphs = sidebar.items.filterIsInstance<Paragraph>()
        assertTrue(paragraphs.size >= 3, "Sidebar should contain multiple paragraphs")
    }

    @Test
    fun testFigure() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Text
                    <figure style="fig" alt="Creation scene" file="creation.jpg" size="col" ref="GEN 1:1">Caption text</figure><verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val figures = para.items.filterIsInstance<Figure>()

        assertEquals(1, figures.size)
        assertEquals("fig", figures[0].style)
        assertEquals("Creation scene", figures[0].alt)
        assertEquals("creation.jpg", figures[0].file)
        assertEquals("col", figures[0].size)
        assertEquals("GEN 1:1", figures[0].ref)
    }

    @Test
    fun testBreakElement() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Before break<optbreak/>After break<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val breaks = para.items.filterIsInstance<Break>()
        assertTrue(breaks.isNotEmpty(), "Should contain Break elements")
    }

    @Test
    fun testBreakStringContextWithNewLines() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Before<optbreak/>After<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val withNewLines = para.toString(StringContext(includeNewLines = true))
        val withoutNewLines = para.toString(StringContext(includeNewLines = false))

        assertTrue(withoutNewLines.contains("Before"))
        assertTrue(withoutNewLines.contains("After"))
    }

    @Test
    fun testMilestone() {
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
        assertTrue(milestones.isNotEmpty(), "Should contain Milestone elements")
    }

    @Test
    fun testRefElement() {
        val usxXml = """
            <usx version="3.0">
                <book code="NUM" style="id">Numbers</book>
                <para style="h">Numbers</para>
                <para style="mt1">Numbers</para>
                <para style="ip">
                    See <char link-href="NUM 3:1" style="xt"><ref loc="NUM 3:1">Numbers 3:1</ref></char>.
                </para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val intro = usx.items.filterIsInstance<BookIntroduction>().first()
        val chars = intro.items.filterIsInstance<UsxChar>()
        assertTrue(chars.isNotEmpty())

        val refs = chars.flatMap { it.items.filterIsInstance<UsxReference>() }
        assertTrue(refs.isNotEmpty(), "Should contain ref elements")
        assertEquals("NUM 3:1", refs[0].loc)
    }
}
