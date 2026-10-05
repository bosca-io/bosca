package bible.grammar

import bosca.bible.grammar.Compiler
import bosca.bible.usx.*
import bosca.bible.usx.Char as UsxChar
import bosca.bible.CharStyle
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for character-level styling elements including nested chars,
 * different char styles, and intro/list char types.
 */
class CharAndStylingTest {

    @Test
    fun testWordWithLexicalAttributes() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" /><char style="w" strong="H7225">beginning</char><verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val word = chapter.items.filterIsInstance<Paragraph>().first()
            .items.filterIsInstance<UsxChar>().first()

        assertEquals(CharStyle.w, word.style)
        assertTrue(word.toString(StringContext.default).contains("beginning"))
    }

    @Test
    fun testWordsOfJesus() {
        val usxXml = """
            <usx version="3.0">
                <book code="MAT" style="id">Matthew</book>
                <para style="h">Matthew</para>
                <para style="mt1">Matthew</para>
                <chapter number="5" style="c" sid="MAT 5" />
                <para style="p">
                    <verse number="3" style="v" sid="MAT 5:3" /><char style="wj">Blessed are the poor in spirit, for theirs is the kingdom of heaven.</char><verse eid="MAT 5:3" />
                </para>
                <chapter eid="MAT 5" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val chars = para.items.filterIsInstance<UsxChar>()

        assertTrue(chars.isNotEmpty())
        assertEquals(CharStyle.wj, chars[0].style)
        assertTrue(chars[0].toString(StringContext.default).contains("Blessed"))
    }

    @Test
    fun testBoldItalicStyles() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Normal <char style="bd">bold</char> <char style="it">italic</char> <char style="bdit">bold-italic</char> text<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val chars = para.items.filterIsInstance<UsxChar>()

        assertEquals(3, chars.size)
        assertEquals(CharStyle.bd, chars[0].style)
        assertEquals(CharStyle.it, chars[1].style)
        assertEquals(CharStyle.bdit, chars[2].style)
    }

    @Test
    fun testNameOfDeity() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />The <char style="nd">Lord</char> said...<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val chars = para.items.filterIsInstance<UsxChar>()

        assertTrue(chars.any { it.style == CharStyle.nd })
        assertTrue(chars.first { it.style == CharStyle.nd }.toString(StringContext.default).contains("Lord"))
    }

    @Test
    fun testNestedCharElements() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" /><char style="wj">Jesus said <char style="qt">blessed</char> are the meek</char><verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val outerChar = para.items.filterIsInstance<UsxChar>().first()
        assertEquals(CharStyle.wj, outerChar.style)

        val innerChars = outerChar.items.filterIsInstance<UsxChar>()
        assertTrue(innerChars.isNotEmpty(), "Should contain nested char element")
        assertEquals(CharStyle.qt, innerChars[0].style)
    }

    @Test
    fun testAdditionStyle() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />The text <char style="add">added words</char> continues<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val chars = para.items.filterIsInstance<UsxChar>()

        assertTrue(chars.any { it.style == CharStyle.add })
    }

    @Test
    fun testSmallCapsStyle() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />The <char style="sc">Lord</char> spoke<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val chars = para.items.filterIsInstance<UsxChar>()

        assertTrue(chars.any { it.style == CharStyle.sc })
    }

    @Test
    fun testIntroChar() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
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
        assertTrue(introChars.isNotEmpty(), "Should contain IntroChar elements")
    }

    @Test
    fun testListChar() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="li">
                    <char style="lik">Key</char>: <char style="liv1">Value</char>
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val list = chapter.items.filterIsInstance<bosca.bible.usx.List>().first()
        val listChars = list.items.filterIsInstance<ListChar>()
        assertTrue(listChars.isNotEmpty(), "Should contain ListChar elements")
    }

    @Test
    fun testSuperscriptStyle() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />Text<char style="sup">a</char> continues<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val chars = para.items.filterIsInstance<UsxChar>()
        assertTrue(chars.any { it.style == CharStyle.sup })
    }

    @Test
    fun testMultipleCharStylesInParagraph() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />The <char style="nd">Lord</char> <char style="sc">God</char> <char style="bd">created</char><verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val chapter = usx.items.filterIsInstance<Chapter>().first()
        val para = chapter.items.filterIsInstance<Paragraph>().first()
        val chars = para.items.filterIsInstance<UsxChar>()

        assertEquals(3, chars.size)
        assertEquals(CharStyle.nd, chars[0].style)
        assertEquals(CharStyle.sc, chars[1].style)
        assertEquals(CharStyle.bd, chars[2].style)
    }
}
