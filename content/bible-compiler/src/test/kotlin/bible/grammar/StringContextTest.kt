package bible.grammar

import bosca.bible.grammar.Compiler
import bosca.bible.usx.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for StringContext output modes: verse numbers, new lines,
 * footnotes, and cross-references toggling.
 */
class StringContextTest {

    private fun compileTestDoc(): Root {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />In the beginning God created the heaven and the earth.
                    <note caller="+" style="f">
                        <char closed="false" style="ft">footnote content here</char>
                    </note>
                    <note caller="-" style="x">
                        <char style="xt">cross reference content</char>
                    </note><verse eid="GEN 1:1" />
                    <verse number="2" style="v" sid="GEN 1:2" />And the earth was without form, and void.<verse eid="GEN 1:2" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        return usx
    }

    @Test
    fun testDefaultContextExcludesAll() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val text = para.toString(StringContext.default)
        assertTrue(text.contains("beginning"))
        assertFalse(text.contains("1."), "Default context should not include verse numbers")
    }

    @Test
    fun testIncludeVerseNumbers() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val text = para.toString(StringContext(includeVerseNumbers = true))
        assertTrue(text.contains("1.") || text.contains("1 "), "Should include verse number 1")
        assertTrue(text.contains("2.") || text.contains("2 "), "Should include verse number 2")
    }

    @Test
    fun testExcludeVerseNumbers() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val withNumbers = para.toString(StringContext(includeVerseNumbers = true))
        val withoutNumbers = para.toString(StringContext(includeVerseNumbers = false))

        assertTrue(withNumbers.length >= withoutNumbers.length)
    }

    @Test
    fun testExcludeFootNotes() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val withoutFootnotes = para.toString(StringContext(includeFootNotes = false))

        assertTrue(withoutFootnotes.contains("beginning"))
        assertFalse(withoutFootnotes.contains("footnote content"))
    }

    @Test
    fun testFootnoteExclusionAtNoteLevel() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val footnote = para.items.filterIsInstance<Footnote>().first()

        val excluded = footnote.toString(StringContext(includeFootNotes = false))
        assertEquals("", excluded, "Footnote should be empty when excluded")
    }

    @Test
    fun testIncludeCrossReferences() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val withXref = para.toString(StringContext(includeCrossReferences = true))
        val withoutXref = para.toString(StringContext(includeCrossReferences = false))

        assertTrue(withXref.contains("cross reference content"))
        assertFalse(withoutXref.contains("cross reference content"))
    }

    @Test
    fun testAllOptionsEnabled() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val context = StringContext(
            includeVerseNumbers = true,
            includeNewLines = true,
            includeFootNotes = true,
            includeCrossReferences = true
        )
        val text = para.toString(context)
        assertTrue(text.contains("beginning"))
    }

    @Test
    fun testAllOptionsDisabled() {
        val root = compileTestDoc()
        val chapter = root.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()

        val context = StringContext(
            includeVerseNumbers = false,
            includeNewLines = false,
            includeFootNotes = false,
            includeCrossReferences = false
        )
        val text = para.toString(context)
        assertTrue(text.contains("beginning"))
        assertFalse(text.contains("footnote content"))
        assertFalse(text.contains("cross reference content"))
    }

    @Test
    fun testTextNewLineHandling() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Line one
                    Line two<verse eid="GEN 1:1" />
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

        assertTrue(withoutNewLines.contains("Line one"))
        assertTrue(withoutNewLines.contains("Line two"))
    }
}
