package bosca.content.collection.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionMetadataItemJobsTest {

    @Test
    fun `Added job preserves id and metadataId`() {
        val id = UUID.random()
        val metadataId = UUID.random()
        val job = CollectionMetadataItemAddedJob(id = id, metadataId = metadataId)
        assertEquals(id, job.id)
        assertEquals(metadataId, job.metadataId)
    }

    @Test
    fun `Added job defaults to null values`() {
        val job = CollectionMetadataItemAddedJob()
        assertNull(job.id)
        assertNull(job.metadataId)
    }

    @Test
    fun `Added job implements CollectionMetadataItemSyncJob`() {
        val id = UUID.random()
        val metadataId = UUID.random()
        val job: CollectionMetadataItemSyncJob = CollectionMetadataItemAddedJob(id = id, metadataId = metadataId)
        assertEquals(id, job.id)
        assertEquals(metadataId, job.metadataId)
    }

    @Test
    fun `Removed job preserves id and metadataId`() {
        val id = UUID.random()
        val metadataId = UUID.random()
        val job = CollectionMetadataItemRemovedJob(id = id, metadataId = metadataId)
        assertEquals(id, job.id)
        assertEquals(metadataId, job.metadataId)
    }

    @Test
    fun `Removed job defaults to null values`() {
        val job = CollectionMetadataItemRemovedJob()
        assertNull(job.id)
        assertNull(job.metadataId)
    }

    @Test
    fun `Removed job implements CollectionMetadataItemSyncJob`() {
        val id = UUID.random()
        val metadataId = UUID.random()
        val job: CollectionMetadataItemSyncJob = CollectionMetadataItemRemovedJob(id = id, metadataId = metadataId)
        assertEquals(id, job.id)
        assertEquals(metadataId, job.metadataId)
    }

    @Test
    fun `Added job data class equality`() {
        val id = UUID.random()
        val metadataId = UUID.random()
        val a = CollectionMetadataItemAddedJob(id = id, metadataId = metadataId)
        val b = CollectionMetadataItemAddedJob(id = id, metadataId = metadataId)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Removed job data class equality`() {
        val id = UUID.random()
        val metadataId = UUID.random()
        val a = CollectionMetadataItemRemovedJob(id = id, metadataId = metadataId)
        val b = CollectionMetadataItemRemovedJob(id = id, metadataId = metadataId)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
