package bosca.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportUrlJobTest {

    @Test
    fun `field preservation with all values`() {
        val id = UUID.random()
        val headers = mapOf("Authorization" to "Bearer token123")
        val job = ImportUrlJob(
            id = id,
            url = "https://example.com/video.mp4",
            contentType = "video/mp4",
            headers = headers
        )
        assertEquals(id, job.id)
        assertEquals("https://example.com/video.mp4", job.url)
        assertEquals("video/mp4", job.contentType)
        assertEquals(headers, job.headers)
    }

    @Test
    fun `optional fields default to null`() {
        val id = UUID.random()
        val job = ImportUrlJob(id = id, url = "https://example.com/file.bin")
        assertNull(job.contentType)
        assertNull(job.headers)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = ImportUrlJob(id = id, url = "https://example.com/file.bin")
        val b = ImportUrlJob(id = id, url = "https://example.com/file.bin")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes url`() {
        val id = UUID.random()
        val job = ImportUrlJob(id = id, url = "https://example.com/old.mp4")
        val modified = job.copy(url = "https://example.com/new.mp4")
        assertEquals("https://example.com/new.mp4", modified.url)
        assertEquals(id, modified.id)
    }
}
