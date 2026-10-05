package bible.grammar

import bosca.bible.grammar.Compiler
import bosca.bible.grammar.CompilerException
import bosca.bible.usx.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Tests for error handling in the compiler, including malformed XML,
 * invalid attributes, and edge cases.
 */
class ErrorHandlingTest {

    @Test
    fun testMalformedXmlThrowsException() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis
            </usx>
        """.trimIndent()

        assertFailsWith<CompilerException> {
            Compiler.compile(Path.of("test.usx"), usxXml)
        }
    }

    @Test
    fun testMinimalValidDocument() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        assertTrue(usx.items.isNotEmpty())
    }

    @Test
    fun testEmptyParagraph() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="b"/>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val paragraphs = chapter.items.filterIsInstance<Paragraph>()
        assertTrue(paragraphs.isNotEmpty())
    }

    @Test
    fun testChapterWithNoVerses() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="s1">Section heading only</para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        assertTrue(para.toString(StringContext.default).contains("Section heading only"))
    }

    @Test
    fun testEmptyVerseText() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" /><verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val verseStarts = para.items.filterIsInstance<VerseStart>()
        assertTrue(verseStarts.isNotEmpty())
    }

    @Test
    fun testLargeChapterNumber() {
        val usxXml = """
            <usx version="3.0">
                <book code="PSA" style="id">Psalms</book>
                <para style="h">Psalms</para>
                <para style="mt1">Psalms</para>
                <chapter number="119" style="c" sid="PSA 119" />
                <para style="p">
                    <verse number="1" style="v" sid="PSA 119:1" />Blessed are they whose ways are blameless<verse eid="PSA 119:1" />
                    <verse number="176" style="v" sid="PSA 119:176" />I have gone astray like a lost sheep<verse eid="PSA 119:176" />
                </para>
                <chapter eid="PSA 119" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        assertTrue(chapter.number == "119")
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val verseStarts = para.items.filterIsInstance<VerseStart>()
        assertTrue(verseStarts.any { it.number == "176" })
    }

    @Test
    fun testUnclosedXmlTag() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Text without verse end
                </para>
        """.trimIndent()

        assertFailsWith<CompilerException> {
            Compiler.compile(Path.of("test.usx"), usxXml)
        }
    }
}
