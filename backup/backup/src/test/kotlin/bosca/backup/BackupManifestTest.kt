package bosca.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that [BackupManifest] survives a JSON serialization round-trip,
 * ensuring the archive manifest written at export time can be read back
 * correctly during restore.
 */
class BackupManifestTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * A manifest with default values and populated counts should serialize
     * to JSON and deserialize back to an equal instance.
     */
    @Test
    fun serializationRoundTrip() {
        val manifest = BackupManifest(
            version = 1,
            format = "bosca-backup",
            createdAt = "2026-03-17T12:00:00Z",
            counts = mutableMapOf("users" to 42L, "metadata" to 100L)
        )
        val encoded = json.encodeToString(manifest)
        val decoded = json.decodeFromString<BackupManifest>(encoded)
        assertEquals(manifest, decoded)
    }

    /**
     * A manifest with an empty counts map should round-trip without error,
     * representing a backup that found no data to export.
     */
    @Test
    fun serializationRoundTripWithEmptyCounts() {
        val manifest = BackupManifest(
            createdAt = "2026-01-01T00:00:00Z"
        )
        val encoded = json.encodeToString(manifest)
        val decoded = json.decodeFromString<BackupManifest>(encoded)
        assertEquals(manifest, decoded)
    }

    /**
     * Confirms that the default values for version and format are applied
     * when only the required createdAt field is supplied.
     */
    @Test
    fun defaultValues() {
        val manifest = BackupManifest(createdAt = "2026-03-17T00:00:00Z")
        assertEquals(1, manifest.version)
        assertEquals("bosca-backup", manifest.format)
        assertTrue(manifest.counts.isEmpty())
    }

    private fun assertTrue(condition: Boolean) {
        kotlin.test.assertTrue(condition)
    }
}
