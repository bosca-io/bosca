package bible.grammar

import bosca.bible.grammar.Compiler
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import bosca.bible.usx.*
import bosca.bible.Reference
import bosca.bible.style.StyleRegistry

class CompilerTest {
    @Test
    fun testCompile() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="h">Genesis</para>
                <para style="mt1">Genesis</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />In the beginning God created the heaven and the earth.<verse eid="GEN 1:1" />
                </para>
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
        val root = usx
        
        println("Parsed Items: ${root.items.size}")
        root.items.forEach { println(it::class.simpleName) }
        
        // Currently implemented: Para, ChapterStart, ChapterEnd.
        // Missing: Book, Table.
        
        assertTrue(root.items.isNotEmpty(), "Root items should not be empty")
    }

    @Test
    fun testCompileMultipleIntros() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="mt1">Genesis</para>
                <para style="imt">Introduction</para>
                <para style="im">Intro paragraph 1</para>
                <para style="im">Intro paragraph 2</para>
                <chapter number="1" style="c" sid="GEN 1" />
                <para style="p">
                    <verse number="1" style="v" sid="GEN 1:1" />In the beginning...<verse eid="GEN 1:1" />
                </para>
                <chapter eid="GEN 1" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test.usx"), usxXml)
        assertIs<Root>(usx)
        val root = usx
        assertTrue(root.items.isNotEmpty(), "Root items should not be empty")
    }

    @Test
    fun testCompileNUMSnippet() {
        val usxXml = """
            <usx version="3.0">
                <book code="NUM" style="id">Numbers</book>
                <para style="h">Numbers</para>
                <para style="mt1">Numbers</para>
                <para style="ip">
                    Numbers reaches back across Leviticus and Exodus and repeats the phrase that structures Genesis:
                    <char style="qt">This is the account of the family of Aaron and Moses</char>
                     (
                    <char link-href="NUM 3:1" style="xt">
                        <ref loc="NUM 3:1">Numbers 3:1</ref>
                    </char>
                    ).
                </para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test-num.usx"), usxXml)
        assertIs<Root>(usx)
        val root = usx
        println("Items count: ${root.items.size}")
        root.items.forEach { item ->
            println("Item: ${item::class.simpleName}, Text: ${item.toString(StringContext.default)}")
        }
        assertTrue(root.items.isNotEmpty(), "Root items should not be empty")
    }

    @Test
    fun testCompileGENFootnote() {
        val usxXml = """
            <usx version="3.0">
                <book code="GEN" style="id">Genesis</book>
                <para style="mt1">Genesis</para>
                <para style="p">
                    Then God said, “Let us make mankind in our image...
                    <note caller="+" style="f">
                        <char closed="false" style="fr">1:26 </char>
                        <char closed="false" style="ft">Probable reading... </char>
                        <char closed="false" style="fq">the </char>
                        <char closed="false" style="fqa">earth</char>
                    </note>
                     and over all the creatures...
                </para>
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test-gen-footnote.usx"), usxXml)
        assertIs<Root>(usx)
        val root = usx
        assertTrue(root.items.isNotEmpty(), "Root items should not be empty")
    }

    @Test
    fun testCompileVerseRange() {
        val usxXml = """
            <usx version="3.0">
                <book code="MAT" style="id">Matthew</book>
                <para style="h">Matthew</para>
                <para style="mt1">Matthew</para>
                <chapter number="5" style="c" sid="MAT 5" />
                <para style="p">
                    <verse number="13" style="v" sid="MAT 5:13" />Verse 13<verse eid="MAT 5:13" />
                </para>
                <para style="p">
                    <verse number="14" style="v" sid="MAT 5:14" />Verse 14<verse eid="MAT 5:14" />
                    <verse number="15" style="v" sid="MAT 5:15" />Verse 15 <char style="wj">red text</char><verse eid="MAT 5:15" />
                    <verse number="16" style="v" sid="MAT 5:16" />Verse 16 <char style="wj">red text</char><verse eid="MAT 5:16" />
                </para>
                <chapter eid="MAT 5" />
            </usx>
        """.trimIndent()

        val usx = Compiler.compile(Path.of("test-mat-range.usx"), usxXml)
        assertIs<Root>(usx)
        val root = usx
        val chapter = root.items.filterIsInstance<Chapter>().first()
        
        val p = chapter.items.filterIsInstance<Paragraph>().last()
        println("Paragraph with range: ${p.toString(StringContext.default)}")
        
        assertTrue(p.toString(StringContext.default).contains("red text"), "Should contain red text from char tag")

        chapter.registry = StyleRegistry()

        // Request individual verse 15, which is part of 15
        val verse15 = chapter[Reference("MAT.5.15")]
        assertNotNull(verse15)
        assertTrue(verse15.toString().contains("Verse 15"))
        
        // Request individual verse 16, which is part of 15-16
        val verse16 = chapter[Reference("MAT.5.16")]
        assertNotNull(verse16)
        assertTrue(verse16.toString().contains("red text"))
    }
}
