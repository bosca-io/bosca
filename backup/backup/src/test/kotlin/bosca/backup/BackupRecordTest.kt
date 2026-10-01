package bosca.backup

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Verifies data class semantics of [BackupRecord], including structural
 * equality, copy behaviour, and default values. These properties are
 * relied upon throughout the backup lifecycle tracking code.
 */
class BackupRecordTest {

    private val now = OffsetDateTime.of(2026, 3, 17, 12, 0, 0, 0, ZoneOffset.UTC)

    private fun createRecord(
        id: Uuid = Uuid.parse("550e8400-e29b-41d4-a716-446655440000"),
        status: String = "pending",
        path: String? = null,
        error: String? = null,
        includeFiles: Boolean = true,
        metadataId: Uuid? = null,
        created: OffsetDateTime = now,
        modified: OffsetDateTime = now,
    ) = BackupRecord(
        id = id,
        status = status,
        path = path,
        error = error,
        includeFiles = includeFiles,
        metadataId = metadataId,
        created = created,
        modified = modified,
    )

    /**
     * Two records constructed with identical field values must be equal
     * under Kotlin data class structural equality.
     */
    @Test
    fun equalityForIdenticalRecords() {
        val a = createRecord()
        val b = createRecord()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /**
     * Changing any single field must break equality, ensuring that the
     * generated equals implementation considers all properties.
     */
    @Test
    fun inequalityWhenFieldsDiffer() {
        val base = createRecord()
        assertNotEquals(base, base.copy(status = "completed"))
        assertNotEquals(base, base.copy(includeFiles = false))
        assertNotEquals(base, base.copy(path = "/backups/archive.zip"))
        assertNotEquals(base, base.copy(error = "disk full"))
    }

    /**
     * The copy function must allow transitioning a record from pending to
     * completed while preserving all other fields, which mirrors the
     * pattern used when the backup executor updates a record.
     */
    @Test
    fun copyPreservesUnchangedFields() {
        val original = createRecord(status = "pending")
        val later = now.plusHours(1)
        val updated = original.copy(
            status = "completed",
            path = "/backups/result.zip",
            modified = later
        )
        assertEquals(original.id, updated.id)
        assertEquals(original.includeFiles, updated.includeFiles)
        assertEquals(original.created, updated.created)
        assertEquals("completed", updated.status)
        assertEquals("/backups/result.zip", updated.path)
        assertEquals(later, updated.modified)
    }

    /**
     * Optional fields (path, error, metadataId) must default to null
     * when not explicitly set, representing an in-progress backup that
     * has not yet produced output or encountered failure.
     */
    @Test
    fun nullableFieldsDefaultToNull() {
        val record = createRecord()
        assertNull(record.path)
        assertNull(record.error)
        assertNull(record.metadataId)
    }

    /**
     * Simulates a failure scenario where the record transitions from
     * pending to failed with an error message, verifying that the error
     * field is correctly carried through the copy.
     */
    @Test
    fun copyToFailedStatePreservesError() {
        val record = createRecord(status = "running")
        val failed = record.copy(status = "failed", error = "I/O timeout")
        assertEquals("failed", failed.status)
        assertEquals("I/O timeout", failed.error)
        assertNull(failed.path)
    }
}
