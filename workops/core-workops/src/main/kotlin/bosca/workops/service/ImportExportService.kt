package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service

// ----- Import -----------------------------------------------------

data class ImportColumn(val index: Int, val header: String, val inferredField: String?)

data class ImportPreview(
    val format: String,
    val columns: List<ImportColumn>,
    val sampleRows: List<List<String>>,
)

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val errors: List<String>,
)

interface ImporterService : Service {
    suspend fun preview(format: String, source: String): ImportPreview
    suspend fun commit(
        format: String,
        source: String,
        projectId: UUID,
        mapping: Map<Int, String>,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        reporterProfileId: UUID,
    ): ImportResult
}

// ----- Export ------------------------------------------------------

interface TaskExportService : Service {
    /**
     * R24 — runs the BQL filter, produces a CSV stream, and
     * returns the bytes. Phase 10 ships the in-process variant;
     * Phase 18 wraps a presigned-URL backed by `core-storage`.
     */
    suspend fun exportCsv(bql: String, actingProfileId: UUID?): ByteArray
}

// ----- Audit retention ---------------------------------------------

interface AuditRetentionService : Service {
    /**
     * R17 — detaches `task_history` partitions older than
     * [retentionMonths]. Returns the names of detached partitions.
     * Phase 10 keeps the cold-storage SQL dump out of scope.
     */
    suspend fun rotate(retentionMonths: Int = 36): List<String>
}
