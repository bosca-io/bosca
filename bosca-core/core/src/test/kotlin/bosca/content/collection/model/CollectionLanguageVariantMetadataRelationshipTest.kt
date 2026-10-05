package bosca.content.collection.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionLanguageVariantMetadataRelationshipTest {

    private val collId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val metaId = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")

    @Test
    fun fieldsArePreserved() {
        val rel = CollectionLanguageVariantMetadataRelationship(
            collectionId = collId,
            metadataId = metaId,
            languageTag = "en-US",
            relationship = "translation"
        )
        assertEquals(collId, rel.collectionId)
        assertEquals(metaId, rel.metadataId)
        assertEquals("en-US", rel.languageTag)
        assertEquals("translation", rel.relationship)
    }

    @Test
    fun id1ReturnsCollectionId() {
        val rel = CollectionLanguageVariantMetadataRelationship(
            collectionId = collId, metadataId = metaId, languageTag = "fr", relationship = "ref"
        )
        assertEquals(collId, rel.id1)
    }

    @Test
    fun id2ReturnsMetadataId() {
        val rel = CollectionLanguageVariantMetadataRelationship(
            collectionId = collId, metadataId = metaId, languageTag = "fr", relationship = "ref"
        )
        assertEquals(metaId, rel.id2)
    }

    @Test
    fun attributesDefaultToNull() {
        val rel = CollectionLanguageVariantMetadataRelationship(
            collectionId = collId, metadataId = metaId, languageTag = "en", relationship = "ref"
        )
        assertNull(rel.attributes)
    }
}
