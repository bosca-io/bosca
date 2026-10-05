package bible.grammar

import bosca.bible.grammar.Compiler
import bosca.bible.usx.*
import bosca.bible.usx.Char as UsxChar
import bosca.bible.BookIdentificationCode
import bosca.bible.BookHeaderStyle
import bosca.bible.BookTitleStyle
import bosca.bible.BookIntroductionStyle
import bosca.bible.BookIntroductionEndTitleStyle
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for book-level structural elements: identification, headers, titles,
 * introductions, and introduction end titles.
 */
class BookStructureTest {

    @Test
    fun testBookIdentificationCode() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis - English Standard Version</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val bookId = usx.items.filterIsInstance<BookIdentification>().first()
        assertEquals(BookIdentificationCode.GEN, bookId.code)
        assertNotNull(bookId.reference)
        assertEquals("GEN", bookId.reference.usfm)
    }

    @Test
    fun testBookIdentificationNewTestament() {
        val usxXml = """
            <usx version="3.0">
                <book code="ROM" style="id">Romans</book>
                <para style="h">Romans</para>
                <para style="mt1">Romans</para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val bookId = usx.items.filterIsInstance<BookIdentification>().first()
        assertEquals(BookIdentificationCode.ROM, bookId.code)
        assertEquals("ROM", bookId.reference!!.usfm)
    }

    @Test
    fun testBookIdentificationDeuterocanon() {
        val usxXml = """
            <usx version="3.0">
                <book code="TOB" style="id">Tobit</book>
                <para style="h">Tobit</para>
                <para style="mt1">Tobit</para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val bookId = usx.items.filterIsInstance<BookIdentification>().first()
        assertEquals(BookIdentificationCode.TOB, bookId.code)
    }

    @Test
    fun testBookHeaders() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="usfm">3.0</para>
                <para style="h">Genesis</para>
                <para style="toc1">Genesis</para>
                <para style="toc2">Gen</para>
                <para style="toc3">Gen</para>
                <para style="mt1">Genesis</para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val headers = usx.items.filterIsInstance<BookHeader>()
        assertEquals(5, headers.size)
        assertEquals(BookHeaderStyle.usfm, headers[0].style)
        assertEquals(BookHeaderStyle.h, headers[1].style)
        assertEquals(BookHeaderStyle.toc1, headers[2].style)
        assertEquals(BookHeaderStyle.toc2, headers[3].style)
        assertEquals(BookHeaderStyle.toc3, headers[4].style)
    }

    @Test
    fun testBookHeaderText() {
        val usxXml = """
            <usx version="3.0">
                <book code="PSA" style="id">Psalms</book>
                <para style="h">Psalms</para>
                <para style="mt1">Psalms</para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val header = usx.items.filterIsInstance<BookHeader>().first()
        assertEquals(BookHeaderStyle.h, header.style)
        assertTrue(header.toString(StringContext.default).contains("Psalms"))
    }

    @Test
    fun testBookTitleSingleLevel() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val titles = usx.items.filterIsInstance<BookTitle>()
        assertEquals(1, titles.size)
        assertEquals(BookTitleStyle.mt1, titles[0].style)
    }

    @Test
    fun testBookTitleMultipleLevels() {
        val usxXml = """
            <usx version="3.0">
                <book code="SNG" style="id">Song of Solomon</book>
                <para style="h">Song of Solomon</para>
                <para style="mt2">The</para>
                <para style="mt1">Song of Solomon</para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val titles = usx.items.filterIsInstance<BookTitle>()
        assertEquals(2, titles.size)
        assertEquals(BookTitleStyle.mt2, titles[0].style)
        assertEquals(BookTitleStyle.mt1, titles[1].style)
    }

    @Test
    fun testBookIntroductionParagraphs() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <para style="ip">The book of Genesis introduces...</para>
                <para style="iot">Outline</para>
                <para style="io1">Creation (1:1-2:25)</para>
                <para style="io1">The Fall (3:1-24)</para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val intros = usx.items.filterIsInstance<BookIntroduction>()
        assertTrue(intros.size >= 4)
        assertEquals(BookIntroductionStyle.ip, intros[0].style)
        assertEquals(BookIntroductionStyle.iot, intros[1].style)
        assertEquals(BookIntroductionStyle.io1, intros[2].style)
    }

    @Test
    fun testBookIntroductionWithIntroChar() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <para style="io1">Creation
                    <char style="ior">1:1-2:25</char>
                </para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val intro = usx.items.filterIsInstance<BookIntroduction>().first()
        val introChars = intro.items.filterIsInstance<IntroChar>()
        assertTrue(introChars.isNotEmpty(), "Introduction should contain IntroChar elements")
    }

    @Test
    fun testBookIntroductionImteStyle() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <para style="ip">Intro text here.</para>
                <para style="imte1">Genesis</para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val intros = usx.items.filterIsInstance<BookIntroduction>()
        assertTrue(intros.any { it.style == BookIntroductionStyle.imte1 },
            "Should contain imte1 introduction style")
    }

    @Test
    fun testFullBookStructure() {
        val usxXml = """
            <usx version="3.0">
                <book code="MAT" style="id">Matthew</book>
                <para style="h">Matthew</para>
                <para style="toc1">The Gospel According to Matthew</para>
                <para style="toc2">Matthew</para>
                <para style="toc3">Matt</para>
                <para style="mt2">The Gospel According to</para>
                <para style="mt1">Matthew</para>
                <para style="imt">INTRODUCTION</para>
                <para style="ip">Matthew presents Jesus as the Messiah.</para>
                <para style="ie"/>
                <chapter number="1" style="c" sid="MAT 1" />
                <para style="p">
                    <verse number="1" style="v" sid="MAT 1:1" />The book of the genealogy of Jesus Christ.<verse eid="MAT 1:1" />
                </para>
                <chapter eid="MAT 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val root = usx

        assertNotNull(root.items.filterIsInstance<BookIdentification>().firstOrNull())
        assertTrue(root.items.filterIsInstance<BookHeader>().size >= 4)
        assertTrue(root.items.filterIsInstance<BookTitle>().size >= 2)
        assertTrue(root.items.filterIsInstance<BookIntroduction>().isNotEmpty())
        assertTrue(root.items.filterIsInstance<Chapter>().isNotEmpty())
    }
}
