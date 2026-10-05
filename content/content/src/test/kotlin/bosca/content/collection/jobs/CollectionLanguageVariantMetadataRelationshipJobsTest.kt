package bosca.content.collection.jobs

import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionLanguageVariantMetadataRelationshipJobsTest {

    private fun createRelationship(): CollectionLanguageVariantMetadataRelationship {
        return CollectionLanguageVariantMetadataRelationship(
            collectionId = UUID.random(),
            metadataId = UUID.random(),
            languageTag = "en",
            relationship = "translates"
        )
    }

    @Test
    fun `Added job preserves id and relationship`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job = CollectionLanguageVariantMetadataRelationshipAddedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
        assertEquals(rel, job.relationship)
    }

    @Test
    fun `Added job defaults id to null`() {
        val rel = createRelationship()
        val job = CollectionLanguageVariantMetadataRelationshipAddedJob(relationship = rel)
        assertNull(job.id)
        assertEquals(rel, job.relationship)
    }

    @Test
    fun `Merged job preserves id and relationship`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job = CollectionLanguageVariantMetadataRelationshipMergedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
        assertEquals(rel, job.relationship)
    }

    @Test
    fun `Merged job defaults id to null`() {
        val rel = createRelationship()
        val job = CollectionLanguageVariantMetadataRelationshipMergedJob(relationship = rel)
        assertNull(job.id)
    }

    @Test
    fun `Removed job preserves id and relationship`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job = CollectionLanguageVariantMetadataRelationshipRemovedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
        assertEquals(rel, job.relationship)
    }

    @Test
    fun `Removed job defaults id to null`() {
        val rel = createRelationship()
        val job = CollectionLanguageVariantMetadataRelationshipRemovedJob(relationship = rel)
        assertNull(job.id)
    }

    @Test
    fun `Added job data class equality`() {
        val id = UUID.random()
        val rel = createRelationship()
        val a = CollectionLanguageVariantMetadataRelationshipAddedJob(id = id, relationship = rel)
        val b = CollectionLanguageVariantMetadataRelationshipAddedJob(id = id, relationship = rel)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Merged job data class equality`() {
        val id = UUID.random()
        val rel = createRelationship()
        val a = CollectionLanguageVariantMetadataRelationshipMergedJob(id = id, relationship = rel)
        val b = CollectionLanguageVariantMetadataRelationshipMergedJob(id = id, relationship = rel)
        assertEquals(a, b)
    }

    @Test
    fun `Removed job data class equality`() {
        val id = UUID.random()
        val rel = createRelationship()
        val a = CollectionLanguageVariantMetadataRelationshipRemovedJob(id = id, relationship = rel)
        val b = CollectionLanguageVariantMetadataRelationshipRemovedJob(id = id, relationship = rel)
        assertEquals(a, b)
    }
}
