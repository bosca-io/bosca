package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChapterInputTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `ChapterInput stores all properties`() {
        val component = buildJsonObject { put("text", "In the beginning...") }
        val reference = ReferenceInput(usfm = "GEN.1")
        val input = ChapterInput(
            component = component,
            reference = reference
        )
        assertEquals(component, input.component)
        assertEquals(reference, input.reference)
    }

    @Test
    fun `ChapterInput with JsonNull component`() {
        val input = ChapterInput(
            component = JsonNull,
            reference = ReferenceInput(usfm = "GEN.2")
        )
        assertEquals(JsonNull, input.component)
    }

    @Test
    fun `data class equality`() {
        val component = buildJsonObject { put("verse", 1) }
        val input1 = ChapterInput(
            component = component,
            reference = ReferenceInput(usfm = "GEN.1")
        )
        val input2 = ChapterInput(
            component = component,
            reference = ReferenceInput(usfm = "GEN.1")
        )
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `data class inequality with different references`() {
        val component = JsonNull
        val input1 = ChapterInput(
            component = component,
            reference = ReferenceInput(usfm = "GEN.1")
        )
        val input2 = ChapterInput(
            component = component,
            reference = ReferenceInput(usfm = "GEN.2")
        )
        assertNotEquals(input1, input2)
    }

    @Test
    fun `toChapter maps all fields correctly`() {
        val component = buildJsonObject { put("content", "chapter text") }
        val input = ChapterInput(
            component = component,
            reference = ReferenceInput(usfm = "GEN.1")
        )
        val chapter = input.toChapter(testId, 2, "standard", "GEN", 0)
        assertEquals(testId, chapter.metadataId)
        assertEquals(2, chapter.version)
        assertEquals("standard", chapter.variant)
        assertEquals("GEN", chapter.bookUsfm)
        assertEquals("GEN.1", chapter.usfm)
        assertEquals(component, chapter.components)
        assertEquals(0, chapter.sort)
    }

    @Test
    fun `toChapter with different sort and variant`() {
        val input = ChapterInput(
            component = JsonNull,
            reference = ReferenceInput(usfm = "EXO.3")
        )
        val chapter = input.toChapter(testId, 1, "catholic", "EXO", 5)
        assertEquals("catholic", chapter.variant)
        assertEquals("EXO", chapter.bookUsfm)
        assertEquals("EXO.3", chapter.usfm)
        assertEquals(5, chapter.sort)
    }

    @Test
    fun `data class copy preserves unchanged fields`() {
        val component = buildJsonObject { put("verse", 1) }
        val input = ChapterInput(
            component = component,
            reference = ReferenceInput(usfm = "GEN.1")
        )
        val copied = input.copy(reference = ReferenceInput(usfm = "GEN.2"))
        assertEquals(component, copied.component)
        assertEquals("GEN.2", copied.reference.usfm)
    }
}
