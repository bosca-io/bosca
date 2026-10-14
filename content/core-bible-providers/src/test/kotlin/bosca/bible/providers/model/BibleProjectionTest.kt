package bosca.bible.providers.model

import bosca.bible.BibleAttribution
import bosca.bible.BibleChapterContent
import bosca.bible.Chapter
import bosca.bible.IBibleChapterContent
import bosca.bible.IChapter
import bosca.bible.Reference
import bosca.bible.bibleJson
import bosca.bible.components.ComponentContainer
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.bible.grammar.Compiler
import bosca.bible.style.StyleRegistry
import bosca.bible.usx.MetadataLanguage
import bosca.bible.usx.Root
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class BibleProjectionTest {

    @Test
    fun `USX chapters retain verse content when materialized and serialized`() {
        val usx = Compiler.compile(
            Path.of("GEN.usx"),
            """
                <usx version="3.0">
                    <book code="GEN" style="id">Genesis</book>
                    <para style="h">Genesis</para>
                    <para style="mt1">Genesis</para>
                    <chapter number="1" style="c" sid="GEN 1" />
                    <para style="p"><verse number="1" style="v" sid="GEN 1:1" />First verse.<verse eid="GEN 1:1" /></para>
                    <para style="p"><verse number="2" style="v" sid="GEN 1:2" />Second verse.<verse eid="GEN 1:2" /></para>
                    <chapter eid="GEN 1" />
                </usx>
            """.trimIndent(),
        )
        val chapter = assertIs<Root>(usx).items.filterIsInstance<bosca.bible.usx.Chapter>().single()
        chapter.registry = StyleRegistry()
        val attribution = BibleAttribution("Copyright", "Citation")
        val content = object : IBibleChapterContent {
            override val chapter: IChapter = chapter
            override val attribution = attribution
        }

        val snapshot = content.asSerializable()
        val restored = bibleJson.decodeFromString(
            BibleChapterContent.serializer(),
            bibleJson.encodeToString(BibleChapterContent.serializer(), snapshot),
        )

        assertEquals(chapter.reference, restored.chapter.reference)
        assertEquals("First verse.", text(assertNotNull(restored.chapter[Reference("GEN.1.1")])).trim())
        assertEquals("Second verse.", text(assertNotNull(restored.chapter[Reference("GEN.1.2")])).trim())
        assertEquals(attribution, restored.attribution)

        val response: IBibleProviderResponse<IChapter> = BibleProviderResponse(chapter, attribution, 2, "usage-token")
        val responseSnapshot = response.asSerializable { it.asSerializable() }
        val serializer = BibleProviderResponse.serializer(Chapter.serializer())
        val restoredResponse = bibleJson.decodeFromString(
            serializer,
            bibleJson.encodeToString(serializer, responseSnapshot),
        )
        assertEquals("First verse.", text(assertNotNull(restoredResponse.content[Reference("GEN.1.1")])).trim())
        assertEquals(attribution, restoredResponse.attribution)
        assertEquals(2, restoredResponse.verseCount)
        assertEquals("usage-token", restoredResponse.fumsToken)
    }

    @Test
    fun `catalog materialization normalizes USX language without retaining its backing map`() {
        val language = mutableMapOf<String, Any>(
            "iso" to "eng",
            "name" to "English",
            "script" to "Latin",
            "scriptCode" to "Latn",
            "scriptDirection" to "LTR",
        )
        val catalog = object : IBibleCatalog {
            override val id = "provider-id"
            override val systemId = "publication-id"
            override val kind = BibleSourceKind.TEXT
            override val name = "Example Bible"
            override val nameLocal = name
            override val abbreviation = "EX"
            override val abbreviationLocal = abbreviation
            override val description = "Example"
            override val languages = listOf(MetadataLanguage(language))
            override val attribution = BibleAttribution("Copyright")
            override val relatedSystemId: String? = null
        }

        val snapshot = catalog.asSerializable()
        language["name"] = "Changed language"
        val restored = bibleJson.decodeFromString(
            BibleCatalog.serializer(),
            bibleJson.encodeToString(BibleCatalog.serializer(), snapshot),
        )

        assertEquals("English", restored.languages.single().name)
        assertEquals("English", restored.languages.single().nameLocal)
        assertEquals("Changed language", catalog.languages.single().name)
        assertEquals(catalog.id, restored.id)
        assertEquals(catalog.attribution, restored.attribution)
    }

    private fun text(component: IComponent): String = when (component) {
        is Text -> component.text
        is ComponentContainer -> component.components.joinToString("") { text(it) }
        else -> ""
    }
}
