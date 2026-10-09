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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiBibleContentTest {

    @Test
    fun `verse filtering preserves paragraphs nested styles and whitespace`() {
        val chapter = chapter("""
            [
              {"type":"tag","name":"para","attrs":{"style":"s1"},"items":[{"type":"text","text":"Heading"}]},
              {"type":"tag","name":"para","attrs":{"style":"p"},"items":[
                {"type":"tag","name":"verse","attrs":{"number":"1"},"items":[{"type":"text","text":"1"}]},
                {"type":"text","text":" First ","attrs":{"verseId":"GEN.1.1"}},
                {"type":"tag","name":"char","attrs":{"style":"nd"},"items":[{"type":"text","text":"styled"}]}
              ]},
              {"type":"tag","name":"para","attrs":{"style":"q1"},"items":[
                {"type":"text","text":" continuation. ","attrs":{"verseId":"GEN.1.1"}},
                {"type":"tag","name":"verse","attrs":{"sid":"GEN 1:2"}},
                {"type":"text","text":"Second."}
              ]}
            ]
        """)

        val first = assertNotNull(chapter[Reference("GEN.1.1")])
        assertEquals(" First styled continuation. ", text(first))
        assertEquals("Second.", text(assertNotNull(chapter[Reference("GEN.1.2")])))
        assertEquals("Heading First styled continuation. Second.", text(assertNotNull(chapter[Reference("GEN.1")])))
        val containers = descendants(first).filterIsInstance<ComponentContainer>().toList()
        assertEquals(listOf("p", "nd", "q1"), containers.mapNotNull { it.style?.id })
        assertTrue(containers.any { it.type == ContainerType.SPAN })
    }

    @Test
    fun `combined ranges organizational references and partial verses retain their text`() {
        val chapter = chapter("""
            [
              {"type":"text","text":"Combined. ","attrs":{"verseId":"GEN.1.1-2"}},
              {"type":"text","text":"Mapped. ","attrs":{"verseId":"GEN.1.3","verseOrgIds":["GEN.1.4","GEN.1.5"]}},
              {"type":"text","text":"Partial.","attrs":{"verseId":"GEN.1.6a"}}
            ]
        """)

        for (usfm in listOf("GEN.1.1", "GEN.1.2")) {
            assertEquals("Combined. ", text(assertNotNull(chapter[Reference(usfm)])))
        }
        for (usfm in listOf("GEN.1.4", "GEN.1.5")) {
            assertEquals("Mapped. ", text(assertNotNull(chapter[Reference(usfm)])))
        }
        assertEquals("Partial.", text(assertNotNull(chapter[Reference("GEN.1.6a")])))
    }

    @Test
    fun `tables and optional breaks remain in the component tree`() {
        val chapter = chapter("""
            [{"type":"tag","name":"table","items":[{"type":"tag","name":"row","items":[
              {"type":"tag","name":"cell","attrs":{"style":"tc1"},"items":[
                {"type":"tag","name":"verse","attrs":{"number":"1"}},
                {"type":"text","text":"Cell"},
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

    private fun chapter(content: String) = Chapter(Reference("GEN.1"), ApiBibleContent("GEN.1").convert(
        Json.decodeFromString(ListSerializer(ApiBibleNode.serializer()), content),
    ))

    private fun text(component: IComponent): String = descendants(component).filterIsInstance<Text>().joinToString("") { it.text }

    private fun descendants(component: IComponent): Sequence<IComponent> = sequence {
        yield(component)
        if (component is ComponentContainer) component.components.forEach { yieldAll(descendants(it)) }
    }
}
