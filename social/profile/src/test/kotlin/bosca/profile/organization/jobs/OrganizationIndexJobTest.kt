package bosca.profile.organization.jobs

import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OrganizationIndexJobTest {

    @Test
    fun `OrganizationIndexJob default values`() {
        val job = OrganizationIndexJob()
        assertFalse(job.deleteFirst)
        assertFalse(job.deleteOnly)
        assertNull(job.storage)
        assertNull(job.id)
    }

    @Test
    fun `OrganizationIndexJob creation with all fields`() {
        val id = UUID.random()
        val storage = IndexStorageSystem(name = "meilisearch")
        val job = OrganizationIndexJob(
            deleteFirst = true,
            deleteOnly = false,
            storage = storage,
            id = id
        )
        assertEquals(true, job.deleteFirst)
        assertEquals(false, job.deleteOnly)
        assertEquals(storage, job.storage)
        assertEquals(id, job.id)
    }

    @Test
    fun `OrganizationIndexJob with deleteFirst true`() {
        val job = OrganizationIndexJob(deleteFirst = true)
        assertEquals(true, job.deleteFirst)
        assertFalse(job.deleteOnly)
    }

    @Test
    fun `OrganizationIndexJob with deleteOnly true`() {
        val job = OrganizationIndexJob(deleteOnly = true)
        assertFalse(job.deleteFirst)
        assertEquals(true, job.deleteOnly)
    }

    @Test
    fun `OrganizationIndexJob data class equality`() {
        val id = UUID.random()
        val a = OrganizationIndexJob(deleteFirst = true, id = id)
        val b = OrganizationIndexJob(deleteFirst = true, id = id)
        assertEquals(a, b)
    }

    @Test
    fun `OrganizationIndexJob data class equality with defaults`() {
        val a = OrganizationIndexJob()
        val b = OrganizationIndexJob()
        assertEquals(a, b)
    }

    @Test
    fun `OrganizationIndexJob copy changes specific fields`() {
        val original = OrganizationIndexJob(deleteFirst = false, deleteOnly = false)
        val copy = original.copy(deleteFirst = true)
        assertEquals(true, copy.deleteFirst)
        assertEquals(false, copy.deleteOnly)
    }

    @Test
    fun `OrganizationIndexJob hashCode is consistent for equal instances`() {
        val a = OrganizationIndexJob(deleteFirst = true, deleteOnly = true)
        val b = OrganizationIndexJob(deleteFirst = true, deleteOnly = true)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `OrganizationIndexJob with storage system`() {
        val storage = IndexStorageSystem(id = UUID.random(), name = "test-storage")
        val job = OrganizationIndexJob(storage = storage)
        assertEquals(storage, job.storage)
    }
}
