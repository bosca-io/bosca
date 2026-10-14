package bosca.bible.providers.apibible

import bosca.bible.Chapter
import bosca.bible.Reference
import bosca.bible.components.Break
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures under `apibible/` are excerpts of real API.Bible chapter responses (content only). */
class ApiBibleContentTest {

    @Test
    fun `section headings and cross reference lines belong to no verse`() {
        val chapter = fixture("bsb-MAT.5.json", "MAT.5")

        assertEquals("When Jesus saw the crowds, He went up on the mountain and sat down. His disciples came to Him, ",
            text(assertNotNull(chapter[Reference("MAT.5.1")])))
        assertEquals("and He began to teach them, saying:", text(assertNotNull(chapter[Reference("MAT.5.2")])))
        assertEquals("“Blessed are the poor in spirit,", text(assertNotNull(chapter[Reference("MAT.5.3")])))
        val whole = text(assertNotNull(chapter[Reference("MAT.5")]))
        assertTrue(whole.startsWith("The Sermon on the Mount"))
        assertTrue(whole.contains("saying:The Beatitudes(Psalms 1:1–6; Luke 6:20–23)“Blessed"))
        val references = descendants(assertNotNull(chapter[Reference("MAT.5")])).filterIsInstance<ComponentContainer>()
            .filter { it.type == ContainerType.DIV && it.style?.id == "" }.toList()
        assertEquals(listOf("Psalms 1:1–6", "Luke 6:20–23"), references.map { text(it) })
    }

    @Test
    fun `verses continue across paragraphs and nested character styles`() {
        val chapter = fixture("web-MAT.5.json", "MAT.5")

        val blessed = assertNotNull(chapter[Reference("MAT.5.3")])
        assertEquals("“Blessed are the poor in spirit,for theirs is the Kingdom of Heaven.", text(blessed))
        val containers = descendants(blessed).filterIsInstance<ComponentContainer>()
            .filter { container -> descendants(container).any { it is Text } }.toList()
        assertEquals(listOf("q1", "wj", "q2", "wj"), containers.mapNotNull { it.style?.id })
        assertTrue(containers.any { it.type == ContainerType.SPAN })
        assertFalse(text(blessed).contains("He opened his mouth"))
    }

    @Test
    fun `verses use the Bible's own numbering and superscriptions belong to no verse`() {
        // KJV Psalm 3 numbers the superscription separately; verseOrgIds shift each verse by one.
        val chapter = fixture("kjv-PSA.3.json", "PSA.3")

        assertEquals("LORD, how are they increased that trouble me! many are they that rise up against me.",
            text(assertNotNull(chapter[Reference("PSA.3.1")])))
        assertTrue(text(assertNotNull(chapter[Reference("PSA.3.2")])).startsWith("Many there be which say of my soul"))
        assertNull(chapter[Reference("PSA.3.3")])
        assertTrue(text(assertNotNull(chapter[Reference("PSA.3")])).startsWith("A Psalm of David, when he fled from Absalom his son."))
    }

    @Test
    fun `combined ranges and partial verses retain their text`() {
        val chapter = chapter("""
            [
              {"type":"text","text":"Combined. ","attrs":{"verseId":"GEN.1.1-2"}},
              {"type":"text","text":"Partial.","attrs":{"verseId":"GEN.1.6a"}}
            ]
        """)

        for (usfm in listOf("GEN.1.1", "GEN.1.2")) {
            assertEquals("Combined. ", text(assertNotNull(chapter[Reference(usfm)])))
        }
        assertEquals("Partial.", text(assertNotNull(chapter[Reference("GEN.1.6a")])))
    }

    @Test
    fun `tables and optional breaks remain in the component tree`() {
        val chapter = chapter("""
            [{"type":"tag","name":"table","items":[{"type":"tag","name":"row","items":[
              {"type":"tag","name":"cell","attrs":{"style":"tc1"},"items":[
                {"type":"tag","name":"verse","attrs":{"number":"1"}},
                {"type":"text","text":"Cell","attrs":{"verseId":"GEN.1.1"}},
                {"type":"tag","name":"optbreak"}
              ]}
            ]}]}]
        """)

        val components = descendants(assertNotNull(chapter[Reference("GEN.1.1")])).toList()
        assertEquals("Cell", components.filterIsInstance<Text>().single().text)
        assertEquals(1, components.filterIsInstance<Break>().size)
        assertEquals(listOf(ContainerType.DIV, ContainerType.TABLE, ContainerType.ROW, ContainerType.COLUMN),
            components.filterIsInstance<ComponentContainer>().map { it.type })
        assertEquals("tc1", components.filterIsInstance<ComponentContainer>().last().style?.id)
    }

    @Test
    fun `unrecognized content and invalid references fail without dropping text`() {
        for (content in listOf(
            """[{"type":"tag","name":"unknown","items":[{"type":"text","text":"Keep me"}]}]""",
            """[{"type":"unknown","text":"Keep me"}]""",
            """[{"type":"text"}]""",
            """[{"type":"tag","name":"verse"}]""",
            """[{"type":"text","text":"Keep me","attrs":{"verseId":"GEN.1.2-1"}}]""",
            """[{"type":"text","text":"Keep me","attrs":{"verseId":"GEN.1.no-verse"}}]""",
            """[{"type":"text","text":"Keep me","attrs":{"verseId":"GEN"}}]""",
        )) {
            assertFailsWith<IOException> { chapter(content) }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String, usfm: String): Chapter = chapter(
        assertNotNull(javaClass.getResource("/apibible/$name")).readText(), usfm,
    )

    private fun chapter(content: String, usfm: String = "GEN.1") = Chapter(Reference(usfm), ApiBibleContent(usfm).convert(
        json.decodeFromString(ListSerializer(ApiBibleNode.serializer()), content),
    ))

    private fun text(component: IComponent): String = descendants(component).filterIsInstance<Text>().joinToString("") { it.text }

    private fun descendants(component: IComponent): Sequence<IComponent> = sequence {
        yield(component)
        if (component is ComponentContainer) component.components.forEach { yieldAll(descendants(it)) }
    }
}
