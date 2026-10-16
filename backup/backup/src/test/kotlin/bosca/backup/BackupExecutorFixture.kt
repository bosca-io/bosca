package bosca.backup

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.core.annotations.Internal
import bosca.db.ConnectionFactory
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the real executors, job context, connection manager, and ZIP I/O with controlled dependencies. */
@OptIn(InternalDI::class, Internal::class)
internal class BackupExecutorFixture {
    val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }
    val backupId = UUID.random()
    val repository = mockk<BackupRepository>(relaxed = true)
    val metadataService = mockk<MetadataService>(relaxed = true)
    val collectionService = mockk<CollectionService>(relaxed = true)
    val storage = mockk<ObjectStorageService>()
    val metadata = mutableListOf<Metadata>()
    val collections = mutableListOf<Collection>()
    val metadataSupplementary = mutableMapOf<UUID, List<MetadataSupplementary>>()
    val collectionSupplementary = mutableMapOf<UUID, List<CollectionSupplementary>>()
    val sourceFiles = mutableMapOf<String, () -> InputStream>()
    val writtenFiles = mutableMapOf<String, ByteArray>()
    val backupMetadata = metadata(contentType = BACKUP_CONTENT_TYPE)
    private val queue = mockk<JobQueue>(relaxed = true)
    private val pool: ConnectionPool

    init {
        ProviderRegistry.clear()
        val factory = mockk<ConnectionFactory>()
        val connection = mockk<Connection>(relaxed = true)
        val statement = mockk<PreparedStatement>(relaxed = true)
        val rows = mockk<ResultSet>()
        var autoCommit = true
        every { factory.key } returns "backup-executor-test"
        every { factory.maxConnections } returns 1
        every { factory.create() } returns connection
        every { connection.autoCommit } answers { autoCommit }
        every { connection.autoCommit = any() } answers { autoCommit = firstArg() }
        every { connection.isValid(any()) } returns true
        every { connection.transactionIsolation } returns Connection.TRANSACTION_READ_COMMITTED
        every { connection.prepareStatement(any()) } returns statement
        every { statement.executeQuery() } returns rows
        every { rows.next() } returns false
        pool = ConnectionPool(factory)
        provides<ConnectionPool> { pool }
        provides<Json> { json }
        provides<BackupRepository> { repository }
        provides<MetadataService> { metadataService }
        provides<CollectionService> { collectionService }
        provides<ObjectStorageService> { storage }

        coEvery { metadataService.getAll(any(), any()) } answers {
            metadata.drop(firstArg<Long>().toInt()).take(secondArg<Int>())
        }
        coEvery { collectionService.getAll(any(), any()) } answers {
            collections.drop(firstArg<Long>().toInt()).take(secondArg<Int>())
        }
        coEvery { metadataService.getSupplementary(any()) } answers {
            metadataSupplementary[firstArg()] ?: emptyList()
        }
        coEvery { collectionService.getSupplementary(any()) } answers {
            collectionSupplementary[firstArg()] ?: emptyList()
        }
        coEvery { metadataService.add(any(), any(), any()) } returns backupMetadata
        coEvery { storage.getPath(any<Metadata>(), any()) } answers {
            val item = firstArg<Metadata>()
            val supplementaryId = secondArg<UUID?>()
            StringObjectPath("metadata/${item.id}/${item.version}/" + (supplementaryId?.let { "supplementary/$it" } ?: "content"))
        }
        coEvery { storage.getPath(any<Collection>(), any()) } answers {
            val item = firstArg<Collection>()
            val supplementaryId = secondArg<UUID?>()
            StringObjectPath("collection/${item.id}/" + (supplementaryId?.let { "supplementary/$it" } ?: "content"))
        }
        coEvery { storage.getInputStream(any()) } answers {
            val path = firstArg<bosca.storage.service.ObjectPath>()
            sourceFiles[path.toString()]?.invoke() ?: throw ObjectNotFoundException(path)
        }
        coEvery { storage.setInputStream(any(), any(), any()) } answers {
            val bytes = secondArg<InputStream>().readBytes()
            writtenFiles[firstArg<bosca.storage.service.ObjectPath>().toString()] = bytes
            bytes.size.toLong()
        }
    }

    suspend fun close() {
        pool.close()
        ProviderRegistry.clear()
    }

    suspend fun backup(includeFiles: Boolean = true) {
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(BackupJob.serializer(), BackupJob(backupId, includeFiles)),
            executor = BackupExecutor::class,
        )
        withContext(queue.asCoroutineContext(job)) { BackupExecutor().execute() }
    }

    suspend fun restore(files: Map<String, ByteArray>) {
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((path, bytes) in files) {
                    zip.putNextEntry(ZipEntry("files/$path"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(BackupManifest.serializer(), BackupManifest(createdAt = "2026-10-15T00:00:00Z")).toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        sourceFiles["backups/input.zip"] = { archive.inputStream() }
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(RestoreJob.serializer(), RestoreJob("backups/input.zip")),
            executor = RestoreExecutor::class,
        )
        withContext(queue.asCoroutineContext(job)) { RestoreExecutor().execute() }
    }

    fun archiveEntries(): Map<String, ByteArray> {
        val bytes = writtenFiles.values.single()
        return buildMap {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    put(entry.name, zip.readBytes())
                    entry = zip.nextEntry
                }
            }
        }
    }

    fun assertPropagated(expected: Throwable, actual: Throwable) {
        assertEquals(expected::class, actual::class)
        assertEquals(expected.message, actual.message)
        // Coroutine stack-trace recovery can copy an exception, preserving it as the cause.
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }

    fun metadata(length: Long? = 4, uploaded: Boolean = true, contentType: String = "application/octet-stream") = Metadata(
        id = UUID.random(), name = "File", type = MetadataType.STANDARD, contentType = contentType,
        contentLength = length, languageTag = "en", workflowStateId = "published",
        uploaded = if (uploaded) OffsetDateTime.now() else null,
    )

    fun collection() = Collection(id = UUID.random(), name = "Collection", languageTag = "en", workflowStateId = "published")
}
