package bosca.content.metadata.model

import bosca.bible.Bible
import bosca.bible.BibleIdentification
import bosca.bible.BibleLanguage
import bosca.bible.BibleMetadata
import bosca.bible.BiblePublication
import bosca.bible.BibleSystem
import bosca.bible.Book
import bosca.bible.Chapter
import bosca.bible.Name
import bosca.bible.Reference
import bosca.bible.bibleJson
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.bible.style.Style
import bosca.bible.style.StyleReference
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BibleInputProjectionTest {
    @Test
    fun `variant selection preserves publication identity language styled content and styles`() {
        val content = Text("Scripture", StyleReference("p"))
        val bible = Bible(BibleMetadata(
            BibleIdentification(BibleSystem("system"), "Bible", "Bible", "Description", "B", "B"),
            BiblePublication("publication", "Bible", "Bible", "Description", "Description", "B", "B"),
            BibleLanguage("eng", "English", "English", "Latin", "Latn", "LTR"),
        ), listOf(Book(Reference("JHN"), Name("JHN", "John", "Jn", "JHN"),
            listOf(Chapter(Reference("JHN.1"), content)))), listOf(Style("p")))

        assertEquals("publication", bible.toInput(true).variant)
        val input = bible.toInput(false, "study")
        assertEquals("study", input.variant)
        assertEquals("system", input.systemId)
        assertEquals("eng", input.languages.single().iso)
        assertEquals(false, input.defaultVariant)
        val restored = assertIs<Text>(bibleJson.decodeFromJsonElement(IComponent.serializer(), input.books.single().chapters.single().component))
        assertEquals(content.text, restored.text)
        assertEquals(content.style?.id, restored.style?.id)
        assertEquals(1, input.styles.jsonArray.size)
    }
}
