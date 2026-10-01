package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BibleTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val styles = buildJsonObject { put("font", "serif") }

    private fun createBible(
        metadataId: Uuid = testId,
        version: Int = 1,
        systemId: String = "sys-1",
        variant: String = "default",
        defaultVariant: Boolean = true,
        name: String = "King James Version",
        nameLocal: String = "King James Version",
        description: String = "A classic English translation",
        abbreviation: String = "KJV",
        abbreviationLocal: String = "KJV",
        styles: kotlinx.serialization.json.JsonElement = this.styles
    ) = Bible(
        metadataId = metadataId,
        version = version,
        systemId = systemId,
        variant = variant,
        defaultVariant = defaultVariant,
        name = name,
        nameLocal = nameLocal,
        description = description,
        abbreviation = abbreviation,
        abbreviationLocal = abbreviationLocal,
        styles = styles
    )

    @Test
    fun `field preservation after construction`() {
        val bible = createBible()
        assertEquals(testId, bible.metadataId)
        assertEquals(1, bible.version)
        assertEquals("sys-1", bible.systemId)
        assertEquals("default", bible.variant)
        assertEquals(true, bible.defaultVariant)
        assertEquals("King James Version", bible.name)
        assertEquals("King James Version", bible.nameLocal)
        assertEquals("A classic English translation", bible.description)
        assertEquals("KJV", bible.abbreviation)
        assertEquals("KJV", bible.abbreviationLocal)
        assertEquals(styles, bible.styles)
    }

    @Test
    fun `data class equality for identical instances`() {
        val bible1 = createBible()
        val bible2 = createBible()
        assertEquals(bible1, bible2)
        assertEquals(bible1.hashCode(), bible2.hashCode())
    }

    @Test
    fun `data class inequality when fields differ`() {
        val bible1 = createBible()
        val bible2 = createBible(version = 2)
        assertNotEquals(bible1, bible2)
    }

    @Test
    fun `copy preserves fields and allows overrides`() {
        val bible = createBible()
        val copy = bible.copy(name = "New Name", version = 3)
        assertEquals("New Name", copy.name)
        assertEquals(3, copy.version)
        assertEquals(bible.metadataId, copy.metadataId)
        assertEquals(bible.systemId, copy.systemId)
        assertEquals(bible.variant, copy.variant)
        assertEquals(bible.defaultVariant, copy.defaultVariant)
        assertEquals(bible.nameLocal, copy.nameLocal)
        assertEquals(bible.description, copy.description)
        assertEquals(bible.abbreviation, copy.abbreviation)
        assertEquals(bible.abbreviationLocal, copy.abbreviationLocal)
        assertEquals(bible.styles, copy.styles)
    }

    @Test
    fun `styles can hold JsonNull`() {
        val bible = createBible(styles = JsonNull)
        assertEquals(JsonNull, bible.styles)
    }

    @Test
    fun `defaultVariant false is preserved`() {
        val bible = createBible(defaultVariant = false)
        assertEquals(false, bible.defaultVariant)
    }
}
