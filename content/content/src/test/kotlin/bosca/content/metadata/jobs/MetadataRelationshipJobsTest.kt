package bosca.content.metadata.jobs

import bosca.content.metadata.model.MetadataRelationship
import bosca.content.model.ContentRelationship
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataRelationshipJobsTest {

    private fun createRelationship(): MetadataRelationship {
        return MetadataRelationship(
            metadataId1 = UUID.random(),
            metadataId2 = UUID.random(),
            relationship = "related"
        )
    }

    @Test
    fun `Added job preserves id and relationship`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job = MetadataRelationshipAddedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
        assertEquals(rel, job.relationship)
    }

    @Test
    fun `Added job defaults id to null`() {
        val rel = createRelationship()
        val job = MetadataRelationshipAddedJob(relationship = rel)
        assertNull(job.id)
    }

    @Test
    fun `Added job implements MetadataSyncJob`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job: MetadataSyncJob = MetadataRelationshipAddedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
        assertEquals(rel, job.relationship)
    }

    @Test
    fun `Merged job preserves id and relationship`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job = MetadataRelationshipMergedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
        assertEquals(rel, job.relationship)
    }

    @Test
    fun `Merged job implements MetadataSyncJob`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job: MetadataSyncJob = MetadataRelationshipMergedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
    }

    @Test
    fun `Removed job preserves id and relationship`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job = MetadataRelationshipRemovedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
        assertEquals(rel, job.relationship)
    }

    @Test
    fun `Removed job implements MetadataSyncJob`() {
        val id = UUID.random()
        val rel = createRelationship()
        val job: MetadataSyncJob = MetadataRelationshipRemovedJob(id = id, relationship = rel)
        assertEquals(id, job.id)
    }

    @Test
    fun `Added job data class equality`() {
        val id = UUID.random()
        val rel = createRelationship()
        val a = MetadataRelationshipAddedJob(id = id, relationship = rel)
        val b = MetadataRelationshipAddedJob(id = id, relationship = rel)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `relationship ContentRelationship interface is accessible`() {
        val rel = createRelationship()
        val job = MetadataRelationshipAddedJob(id = UUID.random(), relationship = rel)
        val contentRel: ContentRelationship? = job.relationship
        assertEquals(rel.metadataId1, contentRel?.id1)
        assertEquals(rel.metadataId2, contentRel?.id2)
        assertEquals("related", contentRel?.relationship)
    }
}
