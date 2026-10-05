package bible.grammar

import bosca.bible.grammar.Compiler
import bosca.bible.processor.HtmlContext
import bosca.bible.usx.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for HTML output generation via HtmlContext, including
 * pretty printing, footnote/cross-reference inclusion, and verse numbers.
 */
class HtmlOutputTest {

    private fun compileTestDoc(): Root {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />In the beginning God created
                    <note caller="+" style="f">
                        <char closed="false" style="ft">Or formed</char>
                    </note>
                     the heaven and the earth.<verse eid="GEN 1:1" />
                    <verse number="2" style="v" sid="GEN 1:2" />And the earth was without form.<verse eid="GEN 1:2" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        return usx
    }

    @Test
    fun testBasicHtmlOutput() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val context = HtmlContext(
            pretty = false,
            includeFootNotes = false,
            includeCrossReferences = false,
            includeVerseNumbers = false
        )
        val html = para.toHtml(context)

        assertTrue(html.contains("<p"), "Should contain paragraph tag")
        assertTrue(html.contains("</p>"), "Should contain closing paragraph tag")
        assertTrue(html.contains("beginning"), "Should contain verse text")
    }

    @Test
    fun testHtmlWithVerseNumbers() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val context = HtmlContext(
            pretty = false,
            includeFootNotes = false,
            includeCrossReferences = false,
            includeVerseNumbers = true
        )
        val html = para.toHtml(context)
        assertTrue(html.contains("1"), "Should contain verse number")
    }

    @Test
    fun testHtmlWithoutFootnotes() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val context = HtmlContext(
            pretty = false,
            includeFootNotes = false,
            includeCrossReferences = false,
            includeVerseNumbers = false
        )
        val html = para.toHtml(context)
        assertFalse(html.contains("Or formed"), "Should not contain footnote text when excluded")
    }

    @Test
    fun testHtmlWithFootnotes() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val context = HtmlContext(
            pretty = false,
            includeFootNotes = true,
            includeCrossReferences = false,
            includeVerseNumbers = false
        )
        val html = para.toHtml(context)
        assertTrue(html.contains("Or formed"), "Should contain footnote text when included")
    }

    @Test
    fun testPrettyHtmlOutput() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val context = HtmlContext(
            pretty = true,
            includeFootNotes = false,
            includeCrossReferences = false,
            includeVerseNumbers = false
        )
        val html = para.toHtml(context)
        assertTrue(html.contains("\n"), "Pretty HTML should contain newlines")
    }

    @Test
    fun testNonPrettyHtmlOutput() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val context = HtmlContext(
            pretty = false,
            includeFootNotes = false,
            includeCrossReferences = false,
            includeVerseNumbers = false
        )
        val html = para.toHtml(context)
        assertFalse(html.startsWith(" "), "Non-pretty HTML should not start with indentation")
    }

    @Test
    fun testTableHtmlOutput() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <table vid="1">
                    <row style="tr">
                        <cell style="tc1" align="start">Cell 1</cell>
                        <cell style="tc2" align="end">Cell 2</cell>
                    </row>
                </table>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val table = chapter.items.filterIsInstance<Table>().first()

        val context = HtmlContext(
            pretty = false,
            includeFootNotes = false,
            includeCrossReferences = false,
            includeVerseNumbers = false
        )
        val html = table.toHtml(context)
        assertTrue(html.contains("<table"), "Should contain table tag")
        assertTrue(html.contains("<tr"), "Should contain row tag")
        assertTrue(html.contains("<td"), "Should contain cell tag")
        assertTrue(html.contains("Cell 1"))
        assertTrue(html.contains("Cell 2"))
    }

    @Test
    fun testHtmlCssClasses() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />The <char style="nd">Lord</char><verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val context = HtmlContext(
            pretty = false,
            includeFootNotes = false,
            includeCrossReferences = false,
            includeVerseNumbers = false
        )
        val html = para.toHtml(context)
        assertTrue(html.contains("class=\"p\""), "Paragraph should have style class")
    }

    @Test
    fun testCrossReferenceHtmlExclusion() {
        val usxXml = """
            <usx version="3.0">
                <book code="MAT" style="id">Matthew</book>
                <para style="h">Matthew</para>
                <para style="mt1">Matthew</para>
                <chapter number="1" style="c" sid="MAT 1" />
                <para style="p">
                    <verse number="1" style="v" sid="MAT 1:1" />Text
                    <note caller="-" style="x">
                        <char style="xt">Isa 7:14</char>
                    </note><verse eid="MAT 1:1" />
                </para>
                <chapter eid="MAT 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val withoutXref = HtmlContext(false, false, false, false)
        val withXref = HtmlContext(false, false, true, false)

        val htmlWithout = para.toHtml(withoutXref)
        val htmlWith = para.toHtml(withXref)

        assertFalse(htmlWithout.contains("Isa 7:14"))
        assertTrue(htmlWith.contains("Isa 7:14"))
    }
}
