package bosca.bible

import kotlin.test.Test
import kotlin.test.assertEquals

class BibleMetadataTest {

    private fun createMetadata(): BibleMetadata {
        val system = BibleSystem(id = "paratext")
        val identification = BibleIdentification(
            system = system,
            name = "Test Bible",
            nameLocal = "TB",
            description = "A test",
            abbreviation = "TB",
            abbreviationLocal = "TB"
        )
        val publication = BiblePublication(
            id = "pub-1",
            name = "Standard",
            nameLocal = "Standard",
            description = "desc",
            descriptionLocal = "desc",
            abbreviation = "S",
            abbreviationLocal = "S"
        )
        val language = BibleLanguage(
            iso = "eng",
            name = "English",
            nameLocal = "English",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        return BibleMetadata(identification, publication, language)
    }

    @Test
    fun fieldPreservation() {
        val metadata = createMetadata()
        assertEquals("Test Bible", metadata.identification.name)
        assertEquals("pub-1", metadata.publication.id)
        assertEquals("eng", metadata.language.iso)
    }

    @Test
    fun asSerializablePreservesNestedObjects() {
        val metadata = createMetadata()
        val serializable = metadata.asSerializable()
        assertEquals("Test Bible", serializable.identification.name)
        assertEquals("pub-1", serializable.publication.id)
        assertEquals("eng", serializable.language.iso)
        assertEquals("paratext", serializable.identification.system.id)
    }
}
