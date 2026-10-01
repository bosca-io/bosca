package bosca.bible

import kotlin.test.Test
import kotlin.test.assertEquals

class BiblePublicationTest {

    @Test
    fun fieldPreservation() {
        val publication = BiblePublication(
            id = "pub-1",
            name = "Standard Edition",
            nameLocal = "Edicion Estandar",
            description = "The standard publication",
            descriptionLocal = "La publicacion estandar",
            abbreviation = "SE",
            abbreviationLocal = "EE"
        )
        assertEquals("pub-1", publication.id)
        assertEquals("Standard Edition", publication.name)
        assertEquals("Edicion Estandar", publication.nameLocal)
        assertEquals("The standard publication", publication.description)
        assertEquals("La publicacion estandar", publication.descriptionLocal)
        assertEquals("SE", publication.abbreviation)
        assertEquals("EE", publication.abbreviationLocal)
    }

    @Test
    fun asSerializablePreservesAllFields() {
        val publication = BiblePublication(
            id = "p1",
            name = "Test",
            nameLocal = "TestLocal",
            description = "desc",
            descriptionLocal = "descLocal",
            abbreviation = "T",
            abbreviationLocal = "TL"
        )
        val serializable = publication.asSerializable()
        assertEquals("p1", serializable.id)
        assertEquals("Test", serializable.name)
        assertEquals("TestLocal", serializable.nameLocal)
        assertEquals("desc", serializable.description)
        assertEquals("descLocal", serializable.descriptionLocal)
        assertEquals("T", serializable.abbreviation)
        assertEquals("TL", serializable.abbreviationLocal)
    }
}
