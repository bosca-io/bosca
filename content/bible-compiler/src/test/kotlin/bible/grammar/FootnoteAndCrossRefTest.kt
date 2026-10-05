package bible.grammar

import bosca.bible.grammar.Compiler
import bosca.bible.usx.*
import bosca.bible.usx.Char as UsxChar
import bosca.bible.usx.Reference as UsxReference
import bosca.bible.FootnoteStyle
import bosca.bible.FootnoteCharStyle
import bosca.bible.CrossReferenceStyle
import bosca.bible.CrossReferenceCharStyle
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for footnote and cross-reference parsing, including nested character
 * styles within notes and StringContext-based filtering.
 */
class FootnoteAndCrossRefTest {

    @Test
    fun testBasicFootnote() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="26" style="v" sid="GEN 1:26" />Then God said, "Let us make mankind."
                    <note caller="+" style="f">
                        <char closed="false" style="fr">1:26 </char>
                        <char closed="false" style="ft">Or adam</char>
                    </note><verse eid="GEN 1:26" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val footnote = para.items.filterIsInstance<Footnote>().firstOrNull()

        assertNotNull(footnote)
        assertEquals(FootnoteStyle.f, footnote.style)
        assertEquals("+", footnote.caller)
    }

    @Test
    fun testFootnoteCharStyles() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Text
                    <note caller="+" style="f">
                        <char closed="false" style="fr">1:1 </char>
                        <char closed="false" style="ft">Some say </char>
                        <char closed="false" style="fq">created </char>
                        <char closed="false" style="fqa">made</char>
                    </note><verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val footnote = para.items.filterIsInstance<Footnote>().first()
        val chars = footnote.items.filterIsInstance<FootnoteChar>()

        assertTrue(chars.size >= 4)
        assertEquals(FootnoteCharStyle.fr, chars[0].style)
        assertEquals(FootnoteCharStyle.ft, chars[1].style)
        assertEquals(FootnoteCharStyle.fq, chars[2].style)
        assertEquals(FootnoteCharStyle.fqa, chars[3].style)
    }

    @Test
    fun testFootnoteStringContextExclusion() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Verse text
                    <note caller="+" style="f">
                        <char closed="false" style="ft">footnote content</char>
                    </note><verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val withoutFootnotes = para.toString(StringContext(includeFootNotes = false))
        val withFootnotes = para.toString(StringContext(includeFootNotes = true))

        assertTrue(withoutFootnotes.contains("Verse text"))
        assertTrue(
            withFootnotes.length >= withoutFootnotes.length,
            "Including footnotes should produce equal or longer output"
        )
    }

    @Test
    fun testEndnoteStyle() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Text
                    <note caller="+" style="fe">
                        <char closed="false" style="ft">Endnote content</char>
                    </note><verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val footnote = para.items.filterIsInstance<Footnote>().first()
        assertEquals(FootnoteStyle.fe, footnote.style)
    }

    @Test
    fun testBasicCrossReference() {
        val usxXml = """
            <usx version="3.0">
                <book code="MAT" style="id">Matthew</book>
                <para style="h">Matthew</para>
                <para style="mt1">Matthew</para>
                <chapter number="1" style="c" sid="MAT 1" />
                <para style="p">
                    <verse number="23" style="v" sid="MAT 1:23" />the virgin shall be with child
                    <note caller="-" style="x">
                        <char style="xo">1:23: </char>
                        <char style="xt">Isa 7:14</char>
                    </note><verse eid="MAT 1:23" />
                </para>
                <chapter eid="MAT 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val xref = para.items.filterIsInstance<CrossReference>().firstOrNull()

        assertNotNull(xref)
        assertEquals(CrossReferenceStyle.x, xref.style)
        assertEquals("-", xref.caller)
    }

    @Test
    fun testCrossReferenceCharStyles() {
        val usxXml = """
            <usx version="3.0">
                <book code="MAT" style="id">Matthew</book>
                <para style="h">Matthew</para>
                <para style="mt1">Matthew</para>
                <chapter number="1" style="c" sid="MAT 1" />
                <para style="p">
                    <verse number="1" style="v" sid="MAT 1:1" />Text
                    <note caller="-" style="x">
                        <char style="xo">1:1: </char>
                        <char style="xt">Gen 22:18; Isa 11:1</char>
                    </note><verse eid="MAT 1:1" />
                </para>
                <chapter eid="MAT 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val xref = para.items.filterIsInstance<CrossReference>().first()
        val chars = xref.items.filterIsInstance<CrossReferenceChar>()

        assertTrue(chars.size >= 2)
        assertEquals(CrossReferenceCharStyle.xo, chars[0].style)
        assertEquals(CrossReferenceCharStyle.xt, chars[1].style)
    }

    @Test
    fun testCrossReferenceStringContextExclusion() {
        val usxXml = """
            <usx version="3.0">
                <book code="MAT" style="id">Matthew</book>
                <para style="h">Matthew</para>
                <para style="mt1">Matthew</para>
                <chapter number="1" style="c" sid="MAT 1" />
                <para style="p">
                    <verse number="1" style="v" sid="MAT 1:1" />Verse text
                    <note caller="-" style="x">
                        <char style="xt">Reference text</char>
                    </note><verse eid="MAT 1:1" />
                </para>
                <chapter eid="MAT 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val withoutXref = para.toString(StringContext(includeCrossReferences = false))
        assertTrue(withoutXref.contains("Verse text"))
    }

    @Test
    fun testMultipleNotesInSingleVerse() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />In the beginning <note caller="+" style="f"><char closed="false" style="ft">Footnote one</char></note> God created <note caller="-" style="x"><char style="xt">Isa 40:21</char></note> the heaven and the earth. <note caller="+" style="f"><char closed="false" style="ft">Footnote two</char></note><verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val footnotes = para.items.filterIsInstance<Footnote>()
        val xrefs = para.items.filterIsInstance<CrossReference>()

        assertEquals(2, footnotes.size)
        assertEquals(1, xrefs.size)
    }

    @Test
    fun testFootnoteWithRefElement() {
        val usxXml = """
            <usx version="3.0">
                <book code="NUM" style="id">Numbers</book>
                <para style="h">Numbers</para>
                <para style="mt1">Numbers</para>
                <para style="ip">
                    See
                    <char link-href="NUM 3:1" style="xt">
                        <ref loc="NUM 3:1">Numbers 3:1</ref>
                    </char>
                    for details.
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
    }
}
