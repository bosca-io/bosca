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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.sql.PreparedStatement
import java.sql.Types
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

                // Use a single connection for the entire restore so that
                // session_replication_role is set and reset on the same connection,
                // and all inserts are wrapped in a single transaction.
                withConnectionManager {
                    val conn = connection()
                    conn.beginTransaction()
                    conn.useStatement("SET session_replication_role = 'replica'") {
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
                        conn.useStatement("SET session_replication_role = 'origin'") {
                            it.execute()
                        }
                        conn.commitTransaction()
                    } catch (e: Exception) {
                        try {
                            conn.useStatement("SET session_replication_role = 'origin'") {
                                it.execute()
                            }
                        } catch (resetError: Exception) {
                            log.error("Failed to reset session_replication_role", resetError)
                        }
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
        val placeholders = columns.joinToString(", ") { "?" }

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

        val sql = "INSERT INTO ${table.qualifiedName} ($columnList) VALUES ($placeholders)$conflictClause"

        val conn = connection()
        var inserted = 0
        for (row in batch) {
            try {
                conn.useStatement(sql) { stmt ->
                    bindJsonValues(stmt, columns, row)
                    inserted += stmt.executeUpdate()
                }
            } catch (e: Exception) {
                if (conflictStrategy == ConflictStrategy.FAIL) {
                    throw e
                }
                log.warn("Failed to insert row into {}: {}", table.name, e.message)
            }
        }

        return inserted
    }

    /**
     * Binds JSON values to a PreparedStatement by inspecting the JSON element type
     * and setting the appropriate JDBC parameter type.
     */
    private fun bindJsonValues(stmt: PreparedStatement, columns: List<String>, row: JsonObject) {
        for ((index, column) in columns.withIndex()) {
            val paramIndex = index + 1
            val value = row[column]

            when {
                value == null || value is JsonNull -> stmt.setNull(paramIndex, Types.OTHER)
                value is JsonPrimitive && value.isString -> stmt.setString(paramIndex, value.content)
                value is JsonPrimitive && value.booleanOrNull != null -> stmt.setBoolean(paramIndex, value.boolean)
                value is JsonPrimitive && value.longOrNull != null -> stmt.setLong(paramIndex, value.long)
                value is JsonPrimitive && value.doubleOrNull != null -> stmt.setDouble(paramIndex, value.double)
                value is JsonObject || value is JsonArray -> {
                    // JSONB columns: pass as PGobject-compatible string
                    val pgObject = org.postgresql.util.PGobject()
                    pgObject.type = "jsonb"
                    pgObject.value = value.toString()
                    stmt.setObject(paramIndex, pgObject)
                }
                else -> stmt.setString(paramIndex, value.toString())
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RestoreExecutor::class.java)
        private const val INSERT_BATCH_SIZE = 100
        private val SAFE_COLUMN_NAME = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
    }
}
