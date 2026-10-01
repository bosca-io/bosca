package bosca.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.serialization.UUIDSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/**
 * Verifies serialization and default-value behaviour of [BackupJob],
 * the payload submitted to the job queue when a backup is initiated.
 */
class BackupJobTest {

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
        }
        encodeDefaults = true
    }

    /**
     * A fully populated [BackupJob] must survive a JSON round-trip so
     * that the queue can reliably persist and restore job payloads.
     */
    @Test
    fun serializationRoundTrip() {
        val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val job = BackupJob(backupId = id, includeFiles = true)
        val encoded = json.encodeToString(job)
        val decoded = json.decodeFromString<BackupJob>(encoded)
        assertEquals(job, decoded)
    }

    /**
     * When includeFiles is omitted the default should be true, meaning
     * binary files are included unless explicitly excluded.
     */
    @Test
    fun defaultIncludeFilesIsTrue() {
        val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val job = BackupJob(backupId = id)
        assertTrue(job.includeFiles)
    }

    /**
     * Setting includeFiles to false must be preserved after serialization
     * so that file-less backups remain file-less when dequeued.
     */
    @Test
    fun includeFilesFalseRoundTrip() {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val job = BackupJob(backupId = id, includeFiles = false)
        val encoded = json.encodeToString(job)
        val decoded = json.decodeFromString<BackupJob>(encoded)
        assertFalse(decoded.includeFiles)
        assertEquals(id, decoded.backupId)
    }
}
