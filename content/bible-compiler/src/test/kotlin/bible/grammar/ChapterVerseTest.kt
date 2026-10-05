package bible.grammar

import bosca.bible.grammar.Compiler
import bosca.bible.usx.*
import bosca.bible.Reference
import bosca.bible.style.StyleRegistry
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for chapter and verse parsing, including multi-chapter documents,
 * verse navigation, verse references, and complex verse structures.
 */
class ChapterVerseTest {

    @Test
    fun testSingleChapterSingleVerse() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />In the beginning God created the heaven and the earth.<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapters = usx.items.filterIsInstance<Chapter>()
        assertEquals(1, chapters.size)
        assertEquals("1", chapters[0].number)
        assertEquals("GEN.1", chapters[0].reference.usfm)
    }

    @Test
    fun testMultipleChapters() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />In the beginning...<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
                <chapter number="2" style="c" sid="GEN 2" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 2:1" />Thus the heavens and the earth were finished.<verse eid="GEN 2:1" />
                </para>
                <chapter eid="GEN 2" />
                <chapter number="3" style="c" sid="GEN 3" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 3:1" />Now the serpent was more crafty.<verse eid="GEN 3:1" />
                </para>
                <chapter eid="GEN 3" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapters = usx.items.filterIsInstance<Chapter>()
        assertEquals(3, chapters.size)
        assertEquals("1", chapters[0].number)
        assertEquals("2", chapters[1].number)
        assertEquals("3", chapters[2].number)
        assertEquals("GEN.1", chapters[0].reference.usfm)
        assertEquals("GEN.2", chapters[1].reference.usfm)
        assertEquals("GEN.3", chapters[2].reference.usfm)
    }

    @Test
    fun testMultipleVersesInParagraph() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />In the beginning God created the heaven and the earth.<verse eid="GEN 1:1" />
                    <verse number="2" style="v" sid="GEN 1:2" />And the earth was without form, and void.<verse eid="GEN 1:2" />
                    <verse number="3" style="v" sid="GEN 1:3" />And God said, Let there be light: and there was light.<verse eid="GEN 1:3" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val verseStarts = para.items.filterIsInstance<VerseStart>()
        val verseEnds = para.items.filterIsInstance<VerseEnd>()

        assertEquals(3, verseStarts.size)
        assertEquals(3, verseEnds.size)
        assertEquals("1", verseStarts[0].number)
        assertEquals("2", verseStarts[1].number)
        assertEquals("3", verseStarts[2].number)
    }

    @Test
    fun testVersesAcrossMultipleParagraphs() {
        val usxXml = """
            <usx version="3.0">
                <book code="JHN" style="id">John</book>
                <para style="h">John</para>
                <para style="mt1">John</para>
                <chapter number="1" style="c" sid="JHN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="JHN 1:1" />In the beginning was the Word.<verse eid="JHN 1:1" />
                </para>
                <para style="p">
                    <verse number="2" style="v" sid="JHN 1:2" />He was in the beginning with God.<verse eid="JHN 1:2" />
                </para>
                <chapter eid="JHN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val paragraphs = chapter.items.filterIsInstance<Paragraph>()
        assertEquals(2, paragraphs.size)
    }

    @Test
    fun testVerseStartAttributes() {
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

        assertEquals("1", verseStart.number)
        assertEquals("GEN 1:1", verseStart.sid)
        assertNotNull(verseStart.reference)
        assertEquals("GEN.1.1", verseStart.reference.usfm)
    }

    @Test
    fun testVerseEndAttributes() {
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

        assertEquals("GEN 1:1", verseEnd.eid)
    }

    @Test
    fun testChapterVerseNavigation() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />In the beginning God created the heaven and the earth.<verse eid="GEN 1:1" />
                    <verse number="2" style="v" sid="GEN 1:2" />And the earth was without form, and void.<verse eid="GEN 1:2" />
                    <verse number="3" style="v" sid="GEN 1:3" />And God said, Let there be light.<verse eid="GEN 1:3" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        chapter.registry = StyleRegistry()

        val verse1 = chapter[Reference("GEN.1.1")]
        assertNotNull(verse1)
        assertTrue(verse1.toString().contains("beginning"))

        val verse2 = chapter[Reference("GEN.1.2")]
        assertNotNull(verse2)
        assertTrue(verse2.toString().contains("without form"))

        val verse3 = chapter[Reference("GEN.1.3")]
        assertNotNull(verse3)
        assertTrue(verse3.toString().contains("Let there be light"))
    }

    @Test
    fun testVerseWithCharStyling() {
        val usxXml = """
            <usx version="3.0">
                <book code="MAT" style="id">Matthew</book>
                <para style="h">Matthew</para>
                <para style="mt1">Matthew</para>
                <chapter number="5" style="c" sid="MAT 5" />
                <para style="p">
                    <verse number="1" style="v" sid="MAT 5:1" />And seeing the multitudes, he went up into a mountain.<verse eid="MAT 5:1" />
                    <verse number="2" style="v" sid="MAT 5:2" />And he opened his mouth, and taught them, saying,<verse eid="MAT 5:2" />
                    <verse number="3" style="v" sid="MAT 5:3" /><char style="wj">Blessed are the poor in spirit: for theirs is the kingdom of heaven.</char><verse eid="MAT 5:3" />
                </para>
                <chapter eid="MAT 5" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        chapter.registry = StyleRegistry()

        val verse3 = chapter[Reference("MAT.5.3")]
        assertNotNull(verse3)
        assertTrue(verse3.toString().contains("Blessed are the poor"))
    }

    @Test
    fun testPoetryParagraphStyles() {
        val usxXml = """
            <usx version="3.0">
                <book code="PSA" style="id">Psalms</book>
                <para style="h">Psalms</para>
                <para style="mt1">Psalms</para>
                <chapter number="23" style="c" sid="PSA 23" />
                <para style="q1">
                    <verse number="1" style="v" sid="PSA 23:1" />The Lord is my shepherd; I shall not want.<verse eid="PSA 23:1" />
                </para>
                <para style="q2">
                    <verse number="2" style="v" sid="PSA 23:2" />He maketh me to lie down in green pastures:<verse eid="PSA 23:2" />
                </para>
                <para style="q2">he leadeth me beside the still waters.</para>
                <para style="b"/>
                <chapter eid="PSA 23" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val paragraphs = chapter.items.filterIsInstance<Paragraph>()
        assertTrue(paragraphs.size >= 3)
    }

    @Test
    fun testSectionHeadings() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="s1">The Creation</para>
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />In the beginning...<verse eid="GEN 1:1" />
                </para>
                <para style="s2">The First Day</para>
                <para style="p">
                    <verse number="2" style="v" sid="GEN 1:2" />And the earth was without form...<verse eid="GEN 1:2" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val paragraphs = chapter.items.filterIsInstance<Paragraph>()
        assertTrue(paragraphs.any { it.style.name == "s1" })
        assertTrue(paragraphs.any { it.style.name == "s2" })
    }
}