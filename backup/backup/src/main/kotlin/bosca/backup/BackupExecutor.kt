package bosca.backup

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.MetadataService
import bosca.db.ConnectionPool
import bosca.db.connection
import bosca.db.withConnectionManager
import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.download
import bosca.storage.service.upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

/**
 * Exports all database records and object storage content into a portable ZIP archive.
 * Each database table is serialized as JSON Lines using PostgreSQL's `row_to_json()`
 * function, enabling streaming without buffering full result sets. Binary files are
 * streamed from the platform's object storage into the archive.
 *
 * The resulting archive is uploaded to object storage at `backups/{backupId}.zip`
 * and the backup tracking record is updated with the final path and status.
 */
@JobDefinition(BackupJob::class, BackupJobQueueNames.backupJobQueue, "backup-system")
class BackupExecutor : AbstractJobExecutor<BackupJob>(BackupJob.serializer()) {

    override suspend fun execute() {
        val jobDef = getJobDefinition()
        val json: Json = provide()
        val objectStorage: ObjectStorageService = provide()
        val backupRepository: BackupRepository = provide()

        withConnectionManager {
            backupRepository.updateStatus(jobDef.backupId, "running")
        }

        val tempFile = createTempFile("bosca-backup-", ".zip")
        try {
            val manifest = BackupManifest(
                createdAt = OffsetDateTime.now().toString()
            )

            ZipOutputStream(tempFile.outputStream().buffered()).use { zip ->
                // Phase 1: Export all database tables as JSONL using a single
                // connection with REPEATABLE READ isolation to ensure a
                // consistent snapshot across all tables and batches.
                withConnectionManager {
                    val conn = connection()
                    conn.beginTransaction()
                    conn.useStatement("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") {
                        it.execute()
                    }

                    try {
                        log.info("Exporting backup with {} tables", BackupTables.ordered.size)
                        for (table in BackupTables.ordered) {
                            exportTable(zip, table, manifest)
                        }
                    } catch (e: Exception) {
                        throw e
                    } finally {
                        conn.rollbackTransaction()
                    }
                }

                // Phase 2: Export binary files from object storage
                if (jobDef.includeFiles) {
                    log.info("Exporting metadata files")
                    exportMetadataFiles(zip, objectStorage)
                    log.info("Exporting collection files")
                    exportCollectionFiles(zip, objectStorage)
                }

                log.info("Exporting complete, writing manifest")
                // Write manifest as the final entry
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
            }

            // Register a metadata item for the backup archive so it can be
            // downloaded through the standard content download API.
            val metadataService: MetadataService = provide()
            val metadata = withConnectionManager {
                metadataService.add(
                    null, null,
                    MetadataInput(
                        name = "Backup ${jobDef.backupId}",
                        languageTag = "en",
                        contentType = BACKUP_CONTENT_TYPE,
                        searchable = false
                    )
                )
            }

            // Upload the archive to the metadata's storage path
            val fileSize = withContext(Dispatchers.IO) {
                Files.size(tempFile)
            }
            objectStorage.upload(metadata, null, tempFile.toFile())

            // Mark the metadata content as uploaded and link to the backup record
            val backupPath = objectStorage.getPath(metadata).toString()
            withConnectionManager {
                metadataService.setUploaded(metadata.id, BACKUP_CONTENT_TYPE, fileSize)
                backupRepository.setMetadataId(jobDef.backupId, metadata.id)
                backupRepository.setPath(jobDef.backupId, backupPath)
                backupRepository.updateStatus(jobDef.backupId, "completed")
            }

            log.info("Backup {} completed successfully, metadata {}", jobDef.backupId, metadata.id)
        } catch (e: Exception) {
            log.error("Backup failed for {}", jobDef.backupId, e)
            withConnectionManager {
                backupRepository.setError(jobDef.backupId, e.message ?: "Unknown error")
            }
            throw e
        } finally {
            tempFile.deleteIfExists()
        }
    }

    /**
     * Exports all rows from a single database table as a JSON Lines file entry
     * inside the ZIP archive. Rows are fetched in batches to stay within
     * connection timeout limits and avoid excessive memory use. Uses the connection
     * from the current coroutine context to participate in the outer REPEATABLE READ
     * transaction for a consistent snapshot.
     */
    private suspend fun exportTable(
        zip: ZipOutputStream,
        table: BackupTableDefinition,
        manifest: BackupManifest
    ) = withContext(Dispatchers.IO) {
        zip.putNextEntry(ZipEntry("data/${table.name}.jsonl"))
        var offset = 0L
        var count = 0L

        while (true) {
            val batch = fetchTableBatch(table, offset, BATCH_SIZE)
            if (batch.isEmpty()) break

            for (row in batch) {
                zip.write(row.toByteArray())
                zip.write(NEWLINE)
                count++
            }

            offset += BATCH_SIZE
        }

        zip.closeEntry()
        manifest.counts[table.name] = count

        if (count > 0) {
            log.info("Exported {} rows from {}", count, table.name)
        }
    }

    /**
     * Fetches a batch of rows from the given table using PostgreSQL's row_to_json()
     * to serialize each row as a JSON string on the database side. Uses the connection
     * from the current coroutine context to ensure all batches read from the same
     * REPEATABLE READ snapshot.
     */
    private suspend fun fetchTableBatch(
        table: BackupTableDefinition,
        offset: Long,
        limit: Int
    ): List<String> {
        val results = mutableListOf<String>()
        val conn = connection()
        conn.useStatement(
            "SELECT row_to_json(t) FROM ${table.qualifiedName} t LIMIT $limit OFFSET $offset"
        ) { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                results.add(rs.getString(1))
            }
        }
        return results
    }

    /**
     * Iterates all metadata entries with uploaded content and copies their primary
     * and supplementary binary files from object storage into the ZIP archive.
     */
    private suspend fun exportMetadataFiles(
        zip: ZipOutputStream,
        objectStorage: ObjectStorageService
    ) = withContext(Dispatchers.IO) {
        val metadataService: MetadataService = provide()
        var offset = 0L

        while (true) {
            val batch = withConnectionManager {
                metadataService.getAll(offset, BATCH_SIZE)
            }
            if (batch.isEmpty()) break

            for (metadata in batch) {
                // Skip other backup archives to avoid recursive inclusion
                if (metadata.contentType == BACKUP_CONTENT_TYPE) continue

                // Export primary content
                val contentLength = metadata.contentLength
                if (metadata.uploaded != null && contentLength != null && contentLength > 0) {
                    try {
                        val entryPath = "files/metadata/${metadata.id}/${metadata.version}/content"
                        zip.putNextEntry(ZipEntry(entryPath))
                        objectStorage.download(metadata).use { it.copyTo(zip) }
                        zip.closeEntry()
                    } catch (e: Exception) {
                        log.warn("Skipping missing file for metadata {}", metadata.id, e)
                    }
                }

                // Export supplementary files
                val supplementaries = withConnectionManager {
                    metadataService.getSupplementary(metadata.id)
                }
                for (supp in supplementaries) {
                    if (supp.uploaded != null) {
                        try {
                            val entryPath = "files/metadata/${metadata.id}/${metadata.version}/supplementary/${supp.id}"
                            zip.putNextEntry(ZipEntry(entryPath))
                            objectStorage.download(metadata, supp.id).use { it.copyTo(zip) }
                            zip.closeEntry()
                        } catch (e: Exception) {
                            log.warn("Skipping missing supplementary {} for metadata {}", supp.id, metadata.id, e)
                        }
                    }
                }
            }

            offset += BATCH_SIZE
        }
    }

    /**
     * Iterates all collections and copies their primary and supplementary binary
     * files from object storage into the ZIP archive.
     */
    private suspend fun exportCollectionFiles(
        zip: ZipOutputStream,
        objectStorage: ObjectStorageService
    ) = withContext(Dispatchers.IO) {
        val collectionService: CollectionService = provide()
        var offset = 0L

        while (true) {
            val batch = withConnectionManager {
                collectionService.getAll(offset, BATCH_SIZE)
            }
            if (batch.isEmpty()) break

            for (collection in batch) {
                // Export primary content if the collection has any
                try {
                    val entryPath = "files/collection/${collection.id}/content"
                    zip.putNextEntry(ZipEntry(entryPath))
                    objectStorage.download(collection).use { it.copyTo(zip) }
                    zip.closeEntry()
                } catch (_: Exception) {
                    // Collections may not have primary content; skip silently
                }

                // Export supplementary files
                val supplementaries = withConnectionManager {
                    collectionService.getSupplementary(collection.id)
                }
                for (supp in supplementaries) {
                    if (supp.uploaded != null) {
                        try {
                            val entryPath = "files/collection/${collection.id}/supplementary/${supp.id}"
                            zip.putNextEntry(ZipEntry(entryPath))
                            objectStorage.download(collection, supp.id).use { it.copyTo(zip) }
                            zip.closeEntry()
                        } catch (e: Exception) {
                            log.warn("Skipping missing supplementary {} for collection {}", supp.id, collection.id, e)
                        }
                    }
                }
            }

            offset += BATCH_SIZE
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BackupExecutor::class.java)
        private const val BATCH_SIZE = 500
        private val NEWLINE = "\n".toByteArray()
    }
}

/** Content type identifier for Bosca backup archives. */
const val BACKUP_CONTENT_TYPE = "bosca/v-backup"
