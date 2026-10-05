package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class BookInputTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `BookInput stores all properties`() {
        val reference = ReferenceInput(usfm = "GEN")
        val chapter = ChapterInput(
            component = kotlinx.serialization.json.JsonNull,
            reference = ReferenceInput(usfm = "GEN.1")
        )
        val input = BookInput(
            abbreviation = "GEN",
            chapters = listOf(chapter),
            nameLong = "Genesis",
            nameShort = "Gen",
            reference = reference
        )
        assertEquals("GEN", input.abbreviation)
        assertEquals(1, input.chapters.size)
        assertEquals(chapter, input.chapters[0])
        assertEquals("Genesis", input.nameLong)
        assertEquals("Gen", input.nameShort)
        assertEquals(reference, input.reference)
    }

    @Test
    fun `BookInput with empty chapters list`() {
        val input = BookInput(
            abbreviation = "EXO",
            chapters = emptyList(),
            nameLong = "Exodus",
            nameShort = "Exo",
            reference = ReferenceInput(usfm = "EXO")
        )
        assertEquals(0, input.chapters.size)
    }

    @Test
    fun `data class equality`() {
        val input1 = BookInput(
            abbreviation = "GEN",
            chapters = emptyList(),
            nameLong = "Genesis",
            nameShort = "Gen",
            reference = ReferenceInput(usfm = "GEN")
        )
        val input2 = BookInput(
            abbreviation = "GEN",
            chapters = emptyList(),
            nameLong = "Genesis",
            nameShort = "Gen",
            reference = ReferenceInput(usfm = "GEN")
        )
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `data class inequality`() {
        val input1 = BookInput(
            abbreviation = "GEN",
            chapters = emptyList(),
            nameLong = "Genesis",
            nameShort = "Gen",
            reference = ReferenceInput(usfm = "GEN")
        )
        val input2 = BookInput(
            abbreviation = "EXO",
            chapters = emptyList(),
            nameLong = "Exodus",
            nameShort = "Exo",
            reference = ReferenceInput(usfm = "EXO")
        )
        assertNotEquals(input1, input2)
    }

    @Test
    fun `toBook maps all fields correctly`() {
        val input = BookInput(
            abbreviation = "GEN",
            chapters = emptyList(),
            nameLong = "Genesis",
            nameShort = "Gen",
            reference = ReferenceInput(usfm = "GEN")
        )
        val book = input.toBook(testId, 1, "standard", 0)
        assertEquals(testId, book.metadataId)
        assertEquals(1, book.version)
        assertEquals("standard", book.variant)
        assertEquals("GEN", book.usfm)
        assertEquals("Gen", book.nameShort)
        assertEquals("Genesis", book.nameLong)
        assertEquals("GEN", book.abbreviation)
        assertEquals(0, book.sort)
    }

    @Test
    fun `toBook with different sort values`() {
        val input = BookInput(
            abbreviation = "EXO",
            chapters = emptyList(),
            nameLong = "Exodus",
            nameShort = "Exo",
            reference = ReferenceInput(usfm = "EXO")
        )
        val book1 = input.toBook(testId, 1, "v1", 0)
        val book2 = input.toBook(testId, 1, "v1", 2)
        assertEquals(0, book1.sort)
        assertEquals(2, book2.sort)
    }

    @Test
    fun `data class copy preserves unchanged fields`() {
        val input = BookInput(
            abbreviation = "GEN",
            chapters = emptyList(),
            nameLong = "Genesis",
            nameShort = "Gen",
            reference = ReferenceInput(usfm = "GEN")
        )
        val copied = input.copy(abbreviation = "EXO")
        assertEquals("EXO", copied.abbreviation)
        assertEquals("Genesis", copied.nameLong)
        assertEquals("Gen", copied.nameShort)
    }
}
