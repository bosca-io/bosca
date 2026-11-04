package bosca.backup

import bosca.db.connection
import bosca.db.withConnectionManager
import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

/**
 * Reads a backup ZIP archive from object storage and restores all contained
 * database records and binary files. Tables are restored in foreign-key
 * dependency order as defined by [BackupTables.ordered]. Conflict handling
 * is determined by the [ConflictStrategy] specified in the job definition.
 */
@JobDefinition(RestoreJob::class, BackupJobQueueNames.backupJobQueue, "restore-system")
class RestoreExecutor : AbstractJobExecutor<RestoreJob>(RestoreJob.serializer()) {

    override suspend fun execute() = withContext(Dispatchers.IO) {
        val jobDef = getJobDefinition()
        val json: Json = provide()
        val objectStorage: ObjectStorageService = provide()

        log.info("Starting restore from {}", jobDef.backupPath)

        require(!jobDef.backupPath.contains("..") && !jobDef.backupPath.startsWith("/")) {
            "Invalid backup path: ${jobDef.backupPath}"
        }

        val tempFile = createTempFile("bosca-restore-", ".zip")
        try {
            // Download archive to temp file
            objectStorage.getInputStream(StringObjectPath(jobDef.backupPath)).use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }

            ZipFile(tempFile.toFile()).use { zipFile ->
                // Validate manifest
                val manifestEntry = zipFile.getEntry("manifest.json")
                    ?: error("Backup archive missing manifest.json")
                val manifest = json.decodeFromString(
                    BackupManifest.serializer(),
                    zipFile.getInputStream(manifestEntry).bufferedReader().readText()
                )
                require(manifest.version == 1) { "Unsupported backup format version: ${manifest.version}" }

                log.info("Restoring backup created at {} with {} tables", manifest.createdAt, manifest.counts.size)

                // A transaction-local setting restores the pooled connection's role on
                // both commit and rollback, including after an insertion failure.
                withConnectionManager {
                    val conn = connection()
                    conn.beginTransaction()
                    conn.useStatement("SET LOCAL session_replication_role = 'replica'") {
                        it.execute()
                    }

                    try {
                        for (table in BackupTables.ordered) {
                            val entryName = "data/${table.name}.jsonl"
                            val entry = zipFile.getEntry(entryName) ?: continue

                            val count = restoreTable(zipFile, entry, table, jobDef.conflictStrategy, json)

                            if (count > 0) {
                                log.info("Restored {} rows into {}", count, table.name)
                            }
                        }
                        conn.commitTransaction()
                    } catch (e: Exception) {
                        conn.rollbackTransaction()
                        throw e
                    }
                }

                // Phase 3: Restore binary files
                val fileEntries = zipFile.entries().asSequence()
                    .filter { it.name.startsWith("files/") && !it.isDirectory }
                    .toList()

                for (entry in fileEntries) {
                    val storagePath = entry.name.removePrefix("files/")
                    if (storagePath.contains("..") || storagePath.startsWith("/")) {
                        log.error("Skipping suspicious path in archive: {}", storagePath)
                        continue
                    }
                    try {
                        zipFile.getInputStream(entry).use { input ->
                            objectStorage.setInputStream(
                                StringObjectPath(storagePath),
                                input,
                                entry.size
                            )
                        }
                    } catch (e: Exception) {
                        log.warn("Failed to restore file {}", storagePath, e)
                    }
                }

                log.info("Restore completed: {} files restored", fileEntries.size)
            }
        } finally {
            tempFile.deleteIfExists()
        }
    }

    /**
     * Reads a JSONL file from the ZIP and inserts each row into the target table.
     * Uses batched inserts for efficiency and handles conflicts according to the
     * specified strategy. Uses the connection from the current coroutine context
     * so all inserts share the same transactional connection.
     */
    private suspend fun restoreTable(
        zipFile: ZipFile,
        entry: ZipEntry,
        table: BackupTableDefinition,
        conflictStrategy: ConflictStrategy,
        json: Json
    ): Long {
        var count = 0L
        val reader = withContext(Dispatchers.IO) {
            zipFile.getInputStream(entry)
        }.bufferedReader()

        reader.useLines { lines ->
            val batch = mutableListOf<JsonObject>()

            for (line in lines) {
                if (line.isBlank()) continue
                val jsonObj = json.decodeFromString(JsonObject.serializer(), line)
                batch.add(jsonObj)

                if (batch.size >= INSERT_BATCH_SIZE) {
                    count += insertBatch(table, batch, conflictStrategy)
                    batch.clear()
                }
            }

            if (batch.isNotEmpty()) {
                count += insertBatch(table, batch, conflictStrategy)
            }
        }

        return count
    }

    /**
     * Inserts a batch of JSON rows into the target table. Constructs a parameterized
     * INSERT statement from the JSON keys and binds values dynamically. Uses the
     * connection from the current coroutine context to participate in the outer
     * transaction.
     */
    private suspend fun insertBatch(
        table: BackupTableDefinition,
        batch: List<JsonObject>,
        conflictStrategy: ConflictStrategy
    ): Int {
        if (batch.isEmpty()) return 0

        // Use the first row's keys to determine columns
        val columns = batch.first().keys.toList()
        for (column in columns) {
            require(column.matches(SAFE_COLUMN_NAME)) {
                "Invalid column name in backup data: $column"
            }
        }
        val columnList = columns.joinToString(", ") { "\"$it\"" }

        val conflictClause = when (conflictStrategy) {
            ConflictStrategy.SKIP -> {
                val pkColumns = table.primaryKeys.joinToString(", ") { "\"$it\"" }
                " ON CONFLICT ($pkColumns) DO NOTHING"
            }
            ConflictStrategy.OVERWRITE -> {
                val pkColumns = table.primaryKeys.joinToString(", ") { "\"$it\"" }
                val updateCols = columns
                    .filter { it !in table.primaryKeys }
                    .joinToString(", ") { "\"$it\" = EXCLUDED.\"$it\"" }
                if (updateCols.isEmpty()) {
                    " ON CONFLICT ($pkColumns) DO NOTHING"
                } else {
                    " ON CONFLICT ($pkColumns) DO UPDATE SET $updateCols"
                }
            }
            ConflictStrategy.FAIL -> ""
        }

        // PostgreSQL applies the table's actual types to its row_to_json representation:
        // UUIDs, timestamps, enums, arrays and JSON columns retain their database types.
        val sql = "INSERT INTO ${table.qualifiedName} ($columnList) SELECT $columnList FROM jsonb_populate_record(NULL::${table.qualifiedName}, ?::jsonb)$conflictClause"

        val conn = connection()
        var inserted = 0
        for (row in batch) {
            conn.useStatement(sql) { stmt ->
                stmt.setString(1, row.toString())
                inserted += stmt.executeUpdate()
            }
        }

        return inserted
    }

    companion object {
        private val log = LoggerFactory.getLogger(RestoreExecutor::class.java)
        private const val INSERT_BATCH_SIZE = 100
        private val SAFE_COLUMN_NAME = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
    }
}
