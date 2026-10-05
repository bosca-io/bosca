package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BibleInputTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    private fun createDefaultBibleInput() = BibleInput(
        systemId = "sys-001",
        name = "Test Bible",
        nameLocal = "Test Bible Local",
        abbreviation = "TB",
        abbreviationLocal = "TBL",
        languages = emptyList(),
        description = "A test bible",
        books = emptyList(),
        defaultVariant = true,
        styles = JsonNull,
        variant = "standard"
    )

    @Test
    fun `BibleInput stores all properties`() {
        val styles = buildJsonObject { put("fontSize", 14) }
        val language = BibleLanguageInput(
            iso = "eng",
            name = "English",
            nameLocal = "English",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        val book = BookInput(
            abbreviation = "GEN",
            chapters = emptyList(),
            nameLong = "Genesis",
            nameShort = "Gen",
            reference = ReferenceInput(usfm = "GEN")
        )
        val input = BibleInput(
            systemId = "sys-002",
            name = "Holy Bible",
            nameLocal = "Santa Biblia",
            abbreviation = "HB",
            abbreviationLocal = "SB",
            languages = listOf(language),
            description = "Full bible description",
            books = listOf(book),
            defaultVariant = false,
            styles = styles,
            variant = "catholic"
        )
        assertEquals("sys-002", input.systemId)
        assertEquals("Holy Bible", input.name)
        assertEquals("Santa Biblia", input.nameLocal)
        assertEquals("HB", input.abbreviation)
        assertEquals("SB", input.abbreviationLocal)
        assertEquals(1, input.languages.size)
        assertEquals(language, input.languages[0])
        assertEquals("Full bible description", input.description)
        assertEquals(1, input.books.size)
        assertEquals(book, input.books[0])
        assertEquals(false, input.defaultVariant)
        assertEquals(styles, input.styles)
        assertEquals("catholic", input.variant)
    }

    @Test
    fun `BibleInput with empty lists`() {
        val input = createDefaultBibleInput()
        assertEquals(0, input.languages.size)
        assertEquals(0, input.books.size)
    }

    @Test
    fun `toBible maps all fields correctly`() {
        val styles = buildJsonObject { put("theme", "dark") }
        val input = BibleInput(
            systemId = "sys-003",
            name = "Bible Name",
            nameLocal = "Bible Local",
            abbreviation = "BN",
            abbreviationLocal = "BL",
            languages = emptyList(),
            description = "Description",
            books = emptyList(),
            defaultVariant = true,
            styles = styles,
            variant = "protestant"
        )
        val bible = input.toBible(testId, 3)
        assertEquals(testId, bible.metadataId)
        assertEquals(3, bible.version)
        assertEquals("sys-003", bible.systemId)
        assertEquals("protestant", bible.variant)
        assertEquals(true, bible.defaultVariant)
        assertEquals("Bible Name", bible.name)
        assertEquals("Bible Local", bible.nameLocal)
        assertEquals("Description", bible.description)
        assertEquals("BN", bible.abbreviation)
        assertEquals("BL", bible.abbreviationLocal)
        assertEquals(styles, bible.styles)
    }

    @Test
    fun `toBible with different version numbers`() {
        val input = createDefaultBibleInput()
        val bible1 = input.toBible(testId, 1)
        val bible2 = input.toBible(testId, 99)
        assertEquals(1, bible1.version)
        assertEquals(99, bible2.version)
    }

    @Test
    fun `toBible with different ids`() {
        val input = createDefaultBibleInput()
        val id1 = Uuid.parse("550e8400-e29b-41d4-a716-446655440001")
        val id2 = Uuid.parse("550e8400-e29b-41d4-a716-446655440002")
        val bible1 = input.toBible(id1, 1)
        val bible2 = input.toBible(id2, 1)
        assertEquals(id1, bible1.metadataId)
        assertEquals(id2, bible2.metadataId)
    }
}
