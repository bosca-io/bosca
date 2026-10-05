package bosca.content.metadata.events

import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Coverage for [UploadProgress] and [METADATA_UPLOAD_PROGRESS_CHANNEL].
 *
 * Exercises the primary constructor, property accessors, the generated
 * data-class members (equals/hashCode/copy/component destructuring/toString),
 * and a full kotlinx.serialization round-trip through a [Json] configured with
 * the module's contextual [UUIDSerializer] (metadataId is `@Contextual`).
 */
class UploadProgressCoverageTest {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
        }
    }

    @Test
    fun `channel constant has expected value`() {
        assertEquals("bosca.content.metadata.upload.progress", METADATA_UPLOAD_PROGRESS_CHANNEL)
    }

    @Test
    fun `constructor preserves all fields`() {
        val id = UUID.random()
        val event = UploadProgress(metadataId = id, bytesUploaded = 512L, totalBytes = 2048L)
        assertEquals(id, event.metadataId)
        assertEquals(512L, event.bytesUploaded)
        assertEquals(2048L, event.totalBytes)
    }

    @Test
    fun `zero and equal byte counts are preserved`() {
        val id = UUID.random()
        val startEvent = UploadProgress(metadataId = id, bytesUploaded = 0L, totalBytes = 0L)
        assertEquals(0L, startEvent.bytesUploaded)
        assertEquals(0L, startEvent.totalBytes)

        val doneEvent = UploadProgress(metadataId = id, bytesUploaded = 4096L, totalBytes = 4096L)
        assertEquals(doneEvent.bytesUploaded, doneEvent.totalBytes)
    }

    @Test
    fun `equal instances are equal and share hashCode`() {
        val id = UUID.random()
        val a = UploadProgress(metadataId = id, bytesUploaded = 100L, totalBytes = 1000L)
        val b = UploadProgress(metadataId = id, bytesUploaded = 100L, totalBytes = 1000L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `differing fields produce unequal instances`() {
        val id = UUID.random()
        val base = UploadProgress(metadataId = id, bytesUploaded = 100L, totalBytes = 1000L)
        assertNotEquals(base, base.copy(metadataId = UUID.random()))
        assertNotEquals(base, base.copy(bytesUploaded = 200L))
        assertNotEquals(base, base.copy(totalBytes = 2000L))
    }

    @Test
    fun `copy overrides selected fields`() {
        val id = UUID.random()
        val original = UploadProgress(metadataId = id, bytesUploaded = 10L, totalBytes = 100L)
        val advanced = original.copy(bytesUploaded = 50L)
        assertEquals(id, advanced.metadataId)
        assertEquals(50L, advanced.bytesUploaded)
        assertEquals(100L, advanced.totalBytes)
    }

    @Test
    fun `component destructuring yields fields in order`() {
        val id = UUID.random()
        val event = UploadProgress(metadataId = id, bytesUploaded = 7L, totalBytes = 70L)
        val (metadataId, bytesUploaded, totalBytes) = event
        assertEquals(id, metadataId)
        assertEquals(7L, bytesUploaded)
        assertEquals(70L, totalBytes)
    }

    @Test
    fun `toString contains field values`() {
        val id = UUID.random()
        val event = UploadProgress(metadataId = id, bytesUploaded = 3L, totalBytes = 30L)
        val text = event.toString()
        assertTrue(text.contains("UploadProgress"))
        assertTrue(text.contains(id.toString()))
        assertTrue(text.contains("3"))
        assertTrue(text.contains("30"))
    }

    @Test
    fun `serialization round-trip preserves equality`() {
        val id = UUID.random()
        val event = UploadProgress(metadataId = id, bytesUploaded = 1234L, totalBytes = 9999L)
        val encoded = json.encodeToString(UploadProgress.serializer(), event)
        val decoded = json.decodeFromString(UploadProgress.serializer(), encoded)
        assertEquals(event, decoded)
        assertEquals(id, decoded.metadataId)
        assertEquals(1234L, decoded.bytesUploaded)
        assertEquals(9999L, decoded.totalBytes)
    }

    @Test
    fun `encoded json carries the contextual uuid and byte counts`() {
        val id = UUID.random()
        val event = UploadProgress(metadataId = id, bytesUploaded = 42L, totalBytes = 84L)
        val encoded = json.encodeToString(UploadProgress.serializer(), event)
        assertTrue(encoded.contains(id.toString()))
        assertTrue(encoded.contains("42"))
        assertTrue(encoded.contains("84"))
    }
}
