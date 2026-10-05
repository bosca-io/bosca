package bosca.content.collection.jobs

import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionMetadataRelationshipJobsTest {

    private fun createRelationship(): CollectionMetadataRelationship {
        return CollectionMetadataRelationship(
            collectionId = UUID.random(),
            metadataId = UUID.random(),
            relationship = "contains"
        )
    }

    @Test
    fun `Added job preserves id and relationship`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job = CollectionMetadataRelationshipAddedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
        assertEquals(rel, job.relationship)
    }

    @Test
    fun `Added job defaults id to null`() {
        val rel = createRelationship()
        val job = CollectionMetadataRelationshipAddedJob(relationship = rel)
        assertNull(job.id)
    }

    @Test
    fun `Merged job preserves id and relationship`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job = CollectionMetadataRelationshipMergedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
        assertEquals(rel, job.relationship)
    }

    @Test
    fun `Merged job defaults id to null`() {
        val rel = createRelationship()
        val job = CollectionMetadataRelationshipMergedJob(relationship = rel)
        assertNull(job.id)
    }

    @Test
    fun `Removed job preserves id and relationship`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job = CollectionMetadataRelationshipRemovedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
        assertEquals(rel, job.relationship)
    }

    @Test
    fun `Removed job defaults id to null`() {
        val rel = createRelationship()
        val job = CollectionMetadataRelationshipRemovedJob(relationship = rel)
        assertNull(job.id)
    }

    @Test
    fun `Added job data class equality`() {
        val id = UUID.random()
        val rel = createRelationship()
        val a = CollectionMetadataRelationshipAddedJob(id = id, relationship = rel)
        val b = CollectionMetadataRelationshipAddedJob(id = id, relationship = rel)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Merged job data class equality`() {
        val id = UUID.random()
        val rel = createRelationship()
        val a = CollectionMetadataRelationshipMergedJob(id = id, relationship = rel)
        val b = CollectionMetadataRelationshipMergedJob(id = id, relationship = rel)
        assertEquals(a, b)
    }

    @Test
    fun `Removed job data class equality`() {
        val id = UUID.random()
        val rel = createRelationship()
        val a = CollectionMetadataRelationshipRemovedJob(id = id, relationship = rel)
        val b = CollectionMetadataRelationshipRemovedJob(id = id, relationship = rel)
        assertEquals(a, b)
    }
}
