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
 * Coverage for [ImportProgress] and [METADATA_IMPORT_PROGRESS_CHANNEL].
 *
 * The data class carries a `@Contextual` [UUID] `metadataId`, so serialization
 * requires a [Json] whose serializers module contextually registers
 * [UUIDSerializer] — mirroring how the production `ImportUrlJob` publishes it.
 */
class ImportProgressCoverageTest {

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
        }
    }

    @Test
    fun `constructor preserves all fields`() {
        val id = UUID.random()
        val progress = ImportProgress(
            metadataId = id,
            bytesDownloaded = 512L,
            totalBytes = 4096L,
        )
        assertEquals(id, progress.metadataId)
        assertEquals(512L, progress.bytesDownloaded)
        assertEquals(4096L, progress.totalBytes)
    }

    @Test
    fun `serialize and deserialize round trip`() {
        val progress = ImportProgress(
            metadataId = UUID.parse("550e8400-e29b-41d4-a716-446655440000"),
            bytesDownloaded = 1024L,
            totalBytes = 8192L,
        )
        val encoded = json.encodeToString(ImportProgress.serializer(), progress)
        val decoded = json.decodeFromString(ImportProgress.serializer(), encoded)
        assertEquals(progress, decoded)
        assertEquals(progress.metadataId, decoded.metadataId)
        assertEquals(progress.bytesDownloaded, decoded.bytesDownloaded)
        assertEquals(progress.totalBytes, decoded.totalBytes)
    }

    @Test
    fun `serialization emits the metadata id as its string form`() {
        val progress = ImportProgress(
            metadataId = UUID.parse("123e4567-e89b-12d3-a456-426614174000"),
            bytesDownloaded = 0L,
            totalBytes = 0L,
        )
        val encoded = json.encodeToString(ImportProgress.serializer(), progress)
        assertTrue(encoded.contains("123e4567-e89b-12d3-a456-426614174000"))
    }

    @Test
    fun `zero progress values round trip`() {
        val progress = ImportProgress(
            metadataId = UUID.random(),
            bytesDownloaded = 0L,
            totalBytes = 0L,
        )
        val decoded = json.decodeFromString(
            ImportProgress.serializer(),
            json.encodeToString(ImportProgress.serializer(), progress),
        )
        assertEquals(progress, decoded)
    }

    @Test
    fun `copy overrides selected fields`() {
        val id = UUID.random()
        val original = ImportProgress(metadataId = id, bytesDownloaded = 100L, totalBytes = 1000L)
        val updated = original.copy(bytesDownloaded = 500L)
        assertEquals(id, updated.metadataId)
        assertEquals(500L, updated.bytesDownloaded)
        assertEquals(1000L, updated.totalBytes)
        assertEquals(original, original.copy())
    }

    @Test
    fun `equals and hashCode reflect value equality`() {
        val id = UUID.random()
        val a = ImportProgress(metadataId = id, bytesDownloaded = 10L, totalBytes = 20L)
        val b = ImportProgress(metadataId = id, bytesDownloaded = 10L, totalBytes = 20L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `differing byte counts are not equal`() {
        val id = UUID.random()
        val a = ImportProgress(metadataId = id, bytesDownloaded = 10L, totalBytes = 20L)
        val c = ImportProgress(metadataId = id, bytesDownloaded = 11L, totalBytes = 20L)
        assertNotEquals(a, c)
    }

    @Test
    fun `toString includes field values`() {
        val progress = ImportProgress(metadataId = UUID.random(), bytesDownloaded = 42L, totalBytes = 84L)
        val text = progress.toString()
        assertTrue(text.contains("42"))
        assertTrue(text.contains("84"))
    }

    @Test
    fun `channel constant has expected value`() {
        assertEquals("bosca.content.metadata.import.progress", METADATA_IMPORT_PROGRESS_CHANNEL)
    }
}
