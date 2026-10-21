package bosca.backup

import kotlinx.serialization.json.JsonObject
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.model.Metadata
import bosca.content.collection.model.Collection
import bosca.server.BoscaApplication
import bosca.content.model.ContentStorageReference
import bosca.content.model.ContentStorageReferenceKind
import bosca.serialization.UUID
import kotlinx.serialization.builtins.ListSerializer
import bosca.server.config.ApplicationConfig
import bosca.storage.service.FileSystemObjectStorageService
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ObjectStorageService
import io.mockk.mockk
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/** Real filesystem stores with fault injection at the object-storage boundary. */
class PostgresBackupTransferTest {
    private lateinit var root: Path
    private lateinit var local: Path
    private lateinit var retained: Path
    private lateinit var application: BoscaApplication
    private lateinit var service: PostgresBackupService
    private lateinit var storage: FileSystemObjectStorageService
    private lateinit var backup: ObjectStorageService
    private val prefix = "postgres/capture"
    private val bytes = "PGDMP".toByteArray() + ByteArray(65537) { (it % 251).toByte() }
    private val manifest = PostgresBackupManifest(
        "capture", "source", "bosca", "2026-10-18T00:00:00Z", "2026-10-18T00:01:00Z",
        "exported", "1:2:", "18", 180000, "UTF8", mapOf("vector" to "0.8.1"),
        bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
    )
    private val writes = mutableListOf<String>()
    private val reads = mutableListOf<String>()
    private val stats = mutableListOf<String>()
    private var opened = 0
    private var closed = 0
    private var storageClosed = false
    private var write: (suspend (ObjectPath, InputStream, Long?) -> Long)? = null
    private var read: (suspend (ObjectPath) -> InputStream)? = null
    private val downloaded get() = root.resolve("download")
    private val payload get() = retained.resolve("$prefix/database.dump")
    private val intent get() = retained.resolve("$prefix/intent.json")
    private val completed get() = retained.resolve("$prefix/manifest.json")

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("bosca-postgres-transfer-")
        local = Files.createDirectory(root.resolve("local"))
        retained = Files.createDirectory(root.resolve("independent"))
        Files.write(local.resolve("database.dump"), bytes)
        local.resolve("manifest.json").writeText(encoded())
        application = BoscaApplication(ApplicationConfig.load("{}".byteInputStream()), ownsServices = false)
        service = PostgresBackupServiceImpl(application, mockk(), mockk())
        storage = FileSystemObjectStorageService("", "", mockk(), retained.toString(), mockk())
        backup = object : ObjectStorageService by storage {
            override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo {
                stats.add(path.toString())
                return storage.getObjectInfo(path)
            }

            override suspend fun getInputStream(path: ObjectPath): InputStream {
                reads.add(path.toString())
                val input = read?.invoke(path) ?: storage.getInputStream(path)
                opened++
                return object : FilterInputStream(input) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        assertTrue(length <= 8192, "Unbounded payload read: $length")
                        return super.read(buffer, offset, length)
                    }

                    override fun close() {
                        closed++
                        super.close()
                    }
                }
            }

            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                writes.add(path.toString())
                return write?.invoke(path, stream, length) ?: storage.setInputStream(path, stream, length)
            }

            override suspend fun delete(path: ObjectPath) = error("Retained objects must not be deleted: $path")

            override fun close() { storageClosed = true }
        }
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        if (::application.isInitialized) application.shutdown()
        if (::root.isInitialized) root.toFile().deleteRecursively()
    }

    private fun reference(
        kind: ContentStorageReferenceKind = ContentStorageReferenceKind.METADATA,
        id: UUID = UUID.random(),
        version: Int? = 7,
        supplementaryId: UUID? = null,
        length: Long? = 5,
    ) = ContentStorageReference(kind, id, version, supplementaryId, null, length, null, false)

    private fun withReferences(vararg references: ContentStorageReference): PostgresBackupManifest = withContentInventory(
        application.json.encodeToString(ListSerializer(ContentStorageReference.serializer()), references.toList()),
        references.size.toLong(),
    )

    private fun retainedEntry(key: String, path: String = "assets/$key", data: String = "image", store: String = "content") = BackupObject(
        store, key, "observation", data.toByteArray().size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).toHexString(), path,
    )

    private fun objectManifest(vararg entries: BackupObject) = ObjectBackupManifest(
        "objects", manifest.sourceId, "content", manifest.created, entries.toList(),
    )

    private fun retain(entry: BackupObject, data: String = "image") {
        retained.resolve(entry.payloadPath).also { Files.createDirectories(it.parent) }.writeText(data)
    }

    private fun withContentInventory(text: String = "[]", count: Long = 0): PostgresBackupManifest {
        val data = text.toByteArray()
        val inventory = PostgresBackupContentInventory(
            count, data.size.toLong(), MessageDigest.getInstance("SHA-256").digest(data).toHexString(),
        )
        Files.write(local.resolve(inventory.path), data)
        return manifest.copy(contentInventory = inventory).also {
            local.resolve("manifest.json").writeText(encoded(it))
        }
    }

    private fun selectionCounts(selected: Long = 1, missing: Long = 0) =
        PostgresBackupContentSelectionStatus.entries.associateWith {
            when (it) {
                PostgresBackupContentSelectionStatus.SELECTED -> selected
                PostgresBackupContentSelectionStatus.MISSING -> missing
                else -> 0L
            }
        }

    @Test
    fun `content report embeds retained artifacts and fresh inspection without source services or storage writes`(): Unit = runBlocking {
        val reference = reference()
        val database = withReferences(reference, reference)
        val entry = retainedEntry("metadata/${reference.contentId}/7/content", "assets/image")
        retain(entry)
        val objects = objectManifest(entry).copy(id = database.id)
        service.upload(local, backup, prefix)
        writes.clear()
        reads.clear()
        val output = root.resolve("recovery.json")
        val rows = mutableListOf<PostgresBackupContentInspection>()
        val report = service.writeContentReport(local, prefix, objects, selectionCounts(2), "content", backup, output) { rows.add(it) }
        assertEquals(database, report.database)
        assertEquals(objects, report.objects)
        assertEquals(prefix, report.databasePath)
        assertEquals(selectionCounts(2), report.selectionCounts)
        assertEquals(2L, report.inspection.counts.getValue(PostgresBackupContentStatus.VERIFIED))
        assertEquals(listOf(reference, reference), rows.map { it.reference })
        assertEquals(1, reads.count { it == entry.payloadPath })
        assertTrue(reads.containsAll(listOf("$prefix/manifest.json", "$prefix/database.dump", "$prefix/content-references.json")))
        assertTrue(writes.isEmpty())
        assertEquals(opened, closed)
        assertFalse(storageClosed)
        read = { error("Reading the saved report must not access retained storage") }
        local.toFile().deleteRecursively()
        retained.toFile().deleteRecursively()
        assertEquals(report, service.readContentReport(output))
        assertEquals(listOf("recovery.json"), root.toFile().list().orEmpty().toList())
    }

    @Test
    fun `content report preserves missing corrupt unresolved and uncopied evidence`(): Unit = runBlocking {
        val missing = reference()
        val corrupt = reference()
        val uncopied = reference()
        val unresolved = reference(ContentStorageReferenceKind.METADATA_SUPPLEMENTARY)
        val database = withReferences(missing, corrupt, uncopied, unresolved)
        val absent = retainedEntry("metadata/${missing.contentId}/7/content", "assets/missing")
        val damaged = retainedEntry("metadata/${corrupt.contentId}/7/content", "assets/corrupt")
        retain(damaged, "wrong")
        service.upload(local, backup, prefix)
        writes.clear()
        val counts = selectionCounts(2, 1) + (PostgresBackupContentSelectionStatus.UNRESOLVED to 1L)
        val report = service.writeContentReport(local, prefix, objectManifest(absent, damaged).copy(id = database.id),
            counts, "content", backup, root.resolve("gaps.json"))
        assertEquals(counts, report.selectionCounts)
        for (status in listOf(PostgresBackupContentStatus.MISSING, PostgresBackupContentStatus.CORRUPT,
            PostgresBackupContentStatus.NOT_RETAINED, PostgresBackupContentStatus.UNRESOLVED)) {
            assertEquals(1L, report.inspection.counts.getValue(status))
        }
        assertEquals(0L, report.inspection.counts.getValue(PostgresBackupContentStatus.VERIFIED))
        assertTrue(writes.isEmpty())
        assertEquals(opened, closed)
    }

    @Test
    fun `content report requires exact completed retained native artifacts`(): Unit = runBlocking {
        val database = withReferences(reference())
        val objects = objectManifest().copy(id = database.id)
        service.upload(local, backup, prefix)
        val completion = completed.readText()
        val referenceBytes = retained.resolve("$prefix/content-references.json").readText()
        val output = root.resolve("report.json")
        for ((index, damage) in listOf<suspend () -> Unit>(
            { Files.delete(completed) },
            { completed.writeText(encoded(database.copy(sourceId = "other"))) },
            { Files.write(payload, bytes.copyOf().also { it[it.lastIndex] = 99 }) },
            { retained.resolve("$prefix/content-references.json").writeText("[]") },
        ).withIndex()) {
            completed.writeText(completion)
            Files.write(payload, bytes)
            retained.resolve("$prefix/content-references.json").writeText(referenceBytes)
            damage()
            val attempt: suspend () -> Unit = {
                service.writeContentReport(local, prefix, objects, selectionCounts(), "content", backup, output)
            }
            when (index) {
                0 -> assertFailsWith<bosca.storage.service.ObjectNotFoundException> { attempt() }
                1 -> assertFailsWith<IllegalArgumentException> { attempt() }
                else -> assertFailsWith<BackupPayloadMismatchException> { attempt() }
            }
            assertFalse(Files.exists(output))
            assertFalse(root.toFile().list().orEmpty().any { it.endsWith(".tmp") })
        }
        assertEquals(opened, closed)
    }

    @Test
    fun `content report never replaces output and rejects invalid evidence without publishing`(): Unit = runBlocking {
        val database = withReferences(reference())
        service.upload(local, backup, prefix)
        val objects = objectManifest().copy(id = database.id)
        val output = root.resolve("report.json")
        output.writeText("keep")
        reads.clear()
        assertFailsWith<IllegalArgumentException> {
            service.writeContentReport(local, prefix, objects, selectionCounts(), "content", backup, output)
        }
        assertEquals("keep", output.readText())
        assertTrue(reads.isEmpty())
        Files.delete(output)
        for (invalid in listOf(objects.copy(id = "other"), objects.copy(sourceId = "other"),
            objects.copy(objects = listOf(retainedEntry("image", "assets/image", store = "other"))))) {
            assertFailsWith<IllegalArgumentException> {
                service.writeContentReport(local, prefix, invalid, selectionCounts(), "content", backup, output)
            }
            assertFalse(Files.exists(output))
        }
        assertFailsWith<IllegalArgumentException> {
            service.writeContentReport(local, prefix, objects, selectionCounts(0), "content", backup, output)
        }
        assertFalse(Files.exists(output))
        assertFalse(root.toFile().list().orEmpty().any { it.endsWith(".tmp") })
    }

    @Test
    fun `content report propagates storage callback and cancellation failures without local output`(): Unit = runBlocking {
        val reference = reference()
        val database = withReferences(reference)
        val entry = retainedEntry("metadata/${reference.contentId}/7/content", "assets/image")
        retain(entry)
        service.upload(local, backup, prefix)
        val objects = objectManifest(entry).copy(id = database.id)
        val output = root.resolve("report.json")
        val failure = IOException("retained read failed")
        read = { throw failure }
        assertEquals(failure.message, assertFailsWith<IOException> {
            service.writeContentReport(local, prefix, objects, selectionCounts(), "content", backup, output)
        }.message)
        read = { path -> if (path.toString() == entry.payloadPath) throw failure else storage.getInputStream(path) }
        assertEquals(failure.message, assertFailsWith<IOException> {
            service.writeContentReport(local, prefix, objects, selectionCounts(), "content", backup, output)
        }.message)
        read = null
        assertEquals(failure.message, assertFailsWith<IOException> {
            service.writeContentReport(local, prefix, objects, selectionCounts(), "content", backup, output) { throw failure }
        }.message)
        assertFailsWith<CancellationException> {
            service.writeContentReport(local, prefix, objects, selectionCounts(), "content", backup, output) {
                throw CancellationException("cancelled report")
            }
        }
        assertFalse(Files.exists(output))
        assertFalse(root.toFile().list().orEmpty().any { it.endsWith(".tmp") })
        assertEquals(opened, closed)
        assertFalse(storageClosed)
    }

    @Test
    fun `content report reader validates saved evidence without storage access`(): Unit = runBlocking {
        val output = root.resolve("report.json")
        output.writeText("{broken")
        assertFailsWith<SerializationException> { service.readContentReport(output) }
        val database = withReferences(reference())
        service.upload(local, backup, prefix)
        val report = service.writeContentReport(local, prefix, objectManifest().copy(id = database.id),
            selectionCounts(), "content", backup, root.resolve("valid.json"))
        output.writeText(application.json.encodeToString(PostgresBackupContentReport.serializer(), report.copy(databasePath = "../invalid")))
        assertFailsWith<IllegalArgumentException> { service.readContentReport(output) }
    }

    @Test
    fun `offline ID paths agree with entity paths for primary and supplementary content`(): Unit = runBlocking {
        val metadata = Metadata(
            id = UUID.random(), version = 7, name = "Image", type = MetadataType.STANDARD,
            contentType = "image/png", contentLength = null, languageTag = "en",
            workflowStateId = "pending", attributes = JsonObject(emptyMap()),
        )
        val collection = Collection(id = UUID.random(), name = "Images", languageTag = "en",
            workflowStateId = "pending", attributes = JsonObject(emptyMap()))
        val supplementary = UUID.random()
        assertEquals("metadata/${metadata.id}/7/content", metadataObjectPath(metadata.id, 7))
        assertEquals("metadata/${metadata.id}/7/supplementary/$supplementary", metadataObjectPath(metadata.id, 7, supplementary))
        assertEquals("collection/${collection.id}/content", collectionObjectPath(collection.id))
        assertEquals("collection/${collection.id}/supplementary/$supplementary", collectionObjectPath(collection.id, supplementary))
        assertEquals(storage.getPath(metadata).toString(), metadataObjectPath(metadata.id, metadata.version))
        assertEquals(storage.getPath(metadata, supplementary).toString(), metadataObjectPath(metadata.id, metadata.version, supplementary))
        assertEquals(storage.getPath(collection).toString(), collectionObjectPath(collection.id))
        assertEquals(storage.getPath(collection, supplementary).toString(), collectionObjectPath(collection.id, supplementary))
    }

    @Test
    fun `content selection uses existing paths once and produces capture input without reading asset bodies`(): Unit = runBlocking {
        val primary = reference()
        val historical = reference(ContentStorageReferenceKind.METADATA_VERSION, id = primary.contentId, version = 1)
        val attachment = reference(ContentStorageReferenceKind.METADATA_SUPPLEMENTARY, supplementaryId = UUID.random())
        val oldAttachment = attachment.copy(kind = ContentStorageReferenceKind.METADATA_VERSION_SUPPLEMENTARY, version = 1)
        val collection = reference(ContentStorageReferenceKind.COLLECTION_SUPPLEMENTARY, version = null, supplementaryId = UUID.random())
        val references = listOf(primary, primary.copy(deleted = true), historical, attachment, oldAttachment, collection)
        val paths = listOf("metadata/${primary.contentId}/7/content", "metadata/${primary.contentId}/1/content",
            "metadata/${attachment.contentId}/7/supplementary/${attachment.supplementaryId}",
            "metadata/${attachment.contentId}/1/supplementary/${attachment.supplementaryId}",
            "collection/${collection.contentId}/supplementary/${collection.supplementaryId}")
        val selected = withReferences(*references.toTypedArray())
        for (path in paths) retain(retainedEntry(path, path))
        val rows = mutableListOf<PostgresBackupContentSelection>()
        val result = service.selectContent(local, "content", "snapshot", backup) { rows.add(it) }
        assertEquals(selected.id, result.inventory.id)
        assertEquals(selected.sourceId, result.inventory.sourceId)
        assertEquals("snapshot", result.inventory.scope)
        java.time.Instant.parse(result.inventory.created)
        assertEquals(6L, result.counts.getValue(PostgresBackupContentSelectionStatus.SELECTED))
        assertEquals(references, rows.map { it.reference })
        assertEquals(paths.sorted(), result.inventory.stores.getValue("content").map { it.key })
        assertEquals(paths, stats)
        assertTrue(rows.all { it.entry?.info?.length == 5L && it.status == PostgresBackupContentSelectionStatus.SELECTED })
        assertEquals(result, application.json.decodeFromString(PostgresBackupContentSelectionSummary.serializer(),
            application.json.encodeToString(PostgresBackupContentSelectionSummary.serializer(), result)))
        result.inventory.validate()
        assertTrue(reads.isEmpty())
        assertTrue(writes.isEmpty())
        assertFalse(storageClosed)
    }

    @Test
    fun `content selection preserves pending deleted and historical gaps without inventing source paths`(): Unit = runBlocking {
        val legacy = reference(ContentStorageReferenceKind.METADATA_VERSION_SUPPLEMENTARY).copy(supplementaryKey = "thumbnail")
        val noVersion = reference(version = null)
        val noAttachment = reference(ContentStorageReferenceKind.METADATA_SUPPLEMENTARY)
        val noCollectionAttachment = reference(ContentStorageReferenceKind.COLLECTION_SUPPLEMENTARY, version = null)
        val missing = reference().copy(deleted = true)
        val mismatch = reference(length = 9)
        val pending = reference(length = null)
        withReferences(legacy, noVersion, noAttachment, noCollectionAttachment, missing, missing, mismatch, pending)
        val mismatchPath = "metadata/${mismatch.contentId}/7/content"
        val pendingPath = "metadata/${pending.contentId}/7/content"
        for (path in listOf(mismatchPath, pendingPath)) retain(retainedEntry(path, path))
        val rows = mutableListOf<PostgresBackupContentSelection>()
        val result = service.selectContent(local, "content", "snapshot", backup) { rows.add(it) }
        assertEquals(mapOf(PostgresBackupContentSelectionStatus.SELECTED to 1L,
            PostgresBackupContentSelectionStatus.UNRESOLVED to 4L, PostgresBackupContentSelectionStatus.MISSING to 2L,
            PostgresBackupContentSelectionStatus.LENGTH_MISMATCH to 1L), result.counts)
        assertTrue(rows.take(4).all { it.path == null && it.entry == null })
        assertEquals(pending, rows.last().reference)
        assertEquals(listOf(pendingPath), result.inventory.stores.getValue("content").map { it.key })
        assertEquals(3, stats.size)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `content selection verifies all local evidence and arguments before source observations`(): Unit = runBlocking {
        val selected = withReferences(reference())
        assertFailsWith<IllegalArgumentException> { service.selectContent(local, " ", "snapshot", backup) }
        assertFailsWith<IllegalArgumentException> { service.selectContent(local, "content", " ", backup) }
        local.resolve("manifest.json").writeText(encoded(manifest))
        assertFailsWith<IllegalArgumentException> { service.selectContent(local, "content", "snapshot", backup) }
        local.resolve("manifest.json").writeText(encoded(selected))
        local.resolve(assertNotNull(selected.contentInventory).path).writeText("[]")
        assertFailsWith<BackupPayloadMismatchException> { service.selectContent(local, "content", "snapshot", backup) }
        assertTrue(stats.isEmpty())
        assertTrue(reads.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `content selection propagates failures cancellation callbacks and unusable observations`(): Unit = runBlocking {
        val ref = reference()
        withReferences(ref)
        val path = "metadata/${ref.contentId}/7/content"
        retain(retainedEntry(path, path))
        for (cause in listOf(IOException("source unavailable"), CancellationException("selection cancelled"))) {
            val failing = object : ObjectStorageService by backup {
                override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo = throw cause
            }
            val actual = assertFailsWith<Exception> { service.selectContent(local, "content", "snapshot", failing) }
            assertEquals(cause::class, actual::class)
            assertEquals(cause.message, actual.message)
        }
        assertEquals("callback failed", assertFailsWith<IOException> {
            service.selectContent(local, "content", "snapshot", backup) { throw IOException("callback failed") }
        }.message)
        val unusable = object : ObjectStorageService by backup {
            override suspend fun getObjectInfo(path: ObjectPath) = ObjectStorageInfo(5)
        }
        assertFailsWith<IllegalArgumentException> { service.selectContent(local, "content", "snapshot", unusable) }
        assertTrue(reads.isEmpty())
        assertTrue(writes.isEmpty())
        assertFalse(storageClosed)
    }

    @Test
    fun `content selection handles a large saved inventory with bounded source access and empty selections`(): Unit = runBlocking {
        val references = List(1002) { reference() }
        withReferences(*references.toTypedArray())
        var lookups = 0
        val source = object : ObjectStorageService by backup {
            override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo {
                lookups++
                return ObjectStorageInfo(5, etag = "observed")
            }
        }
        var rows = 0
        val result = service.selectContent(local, "content", "snapshot", source) { rows++ }
        assertEquals(1002, rows)
        assertEquals(1002, lookups)
        assertEquals(1002L, result.counts.getValue(PostgresBackupContentSelectionStatus.SELECTED))
        assertEquals(1002, result.inventory.stores.getValue("content").size)
        withReferences()
        val empty = service.selectContent(local, "content", "snapshot", source)
        assertEquals(emptyList(), empty.inventory.stores.getValue("content"))
        assertTrue(empty.counts.values.all { it == 0L })
        assertEquals(1002, lookups)
    }

    @Test
    fun `content inspection verifies existing ID paths without a source and checks shared payloads once`(): Unit = runBlocking {
        val primary = reference()
        val supplementary = reference(
            ContentStorageReferenceKind.COLLECTION_SUPPLEMENTARY, version = null, supplementaryId = UUID.random(),
        )
        val attachment = reference(ContentStorageReferenceKind.METADATA_SUPPLEMENTARY, supplementaryId = UUID.random())
        val historical = reference(ContentStorageReferenceKind.METADATA_VERSION, id = primary.contentId, version = 1)
        val selected = withReferences(primary, primary.copy(deleted = true), supplementary, attachment, historical)
        val first = retainedEntry("metadata/${primary.contentId}/7/content", "assets/shared")
        val second = retainedEntry("collection/${supplementary.contentId}/supplementary/${supplementary.supplementaryId}", "assets/shared")
        retain(first)
        val third = retainedEntry("metadata/${attachment.contentId}/7/supplementary/${attachment.supplementaryId}", "assets/shared")
        val fourth = retainedEntry("metadata/${primary.contentId}/1/content", "assets/shared")
        val objects = objectManifest(first, second, third, fourth)
        val rows = mutableListOf<PostgresBackupContentInspection>()
        val summary = service.inspectContent(local, objects, "content", backup) { rows.add(it) }
        assertEquals(selected.id, summary.databaseId)
        assertEquals(objects.id, summary.objectManifestId)
        assertEquals("content", summary.store)
        assertEquals(5L, summary.counts.getValue(PostgresBackupContentStatus.VERIFIED))
        assertEquals(listOf(first.key, first.key, second.key, third.key, fourth.key), rows.map { it.path })
        assertEquals(listOf(primary, primary.copy(deleted = true), supplementary, attachment, historical), rows.map { it.reference })
        assertEquals(listOf(first, first, second, third, fourth), rows.map { it.payload })
        assertEquals(listOf("assets/shared"), reads)
        assertEquals(opened, closed)
        assertTrue(writes.isEmpty())
        assertFalse(storageClosed)
    }

    @Test
    fun `content inspection reports absent identities and pending evidence without inventing primary paths`(): Unit = runBlocking {
        val legacy = reference(ContentStorageReferenceKind.METADATA_VERSION_SUPPLEMENTARY).copy(supplementaryKey = "thumbnail")
        val noVersion = reference(version = null)
        val noAttachment = reference(ContentStorageReferenceKind.METADATA_SUPPLEMENTARY)
        val noCollectionAttachment = reference(ContentStorageReferenceKind.COLLECTION_SUPPLEMENTARY, version = null)
        val pending = reference(length = null)
        withReferences(legacy, noVersion, noAttachment, noCollectionAttachment, pending)
        val unrelated = retainedEntry("metadata/${pending.contentId}/7/content", store = "other")
        val rows = mutableListOf<PostgresBackupContentInspection>()
        val summary = service.inspectContent(local, objectManifest(unrelated), "content", backup) { rows.add(it) }
        assertEquals(4L, summary.counts.getValue(PostgresBackupContentStatus.UNRESOLVED))
        assertEquals(1L, summary.counts.getValue(PostgresBackupContentStatus.NOT_RETAINED))
        assertTrue(rows.take(4).all { it.path == null && it.payload == null })
        assertEquals(pending, rows.last().reference)
        assertEquals("metadata/${pending.contentId}/7/content", rows.last().path)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `content inspection never chooses an ambiguous revision or a different declared length`(): Unit = runBlocking {
        val ambiguous = reference()
        val mismatch = reference(length = 9)
        withReferences(ambiguous, mismatch)
        val entry = retainedEntry("metadata/${ambiguous.contentId}/7/content", "assets/first")
        val other = entry.copy(revision = "another-observation", payloadPath = "assets/second")
        val short = retainedEntry("metadata/${mismatch.contentId}/7/content", "assets/short")
        val rows = mutableListOf<PostgresBackupContentInspection>()
        service.inspectContent(local, objectManifest(entry, other, short), "content", backup) { rows.add(it) }
        assertEquals(listOf(PostgresBackupContentStatus.AMBIGUOUS, PostgresBackupContentStatus.LENGTH_MISMATCH), rows.map { it.status })
        assertEquals(null, rows.first().payload)
        assertEquals(short, rows.last().payload)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `content inspection reports missing and corrupt retained bytes and closes opened streams`(): Unit = runBlocking {
        val missing = reference()
        val corrupt = reference()
        withReferences(missing, corrupt)
        val absent = retainedEntry("metadata/${missing.contentId}/7/content", "assets/missing")
        val damaged = retainedEntry("metadata/${corrupt.contentId}/7/content", "assets/corrupt")
        retain(damaged, "wrong")
        val rows = mutableListOf<PostgresBackupContentInspection>()
        val summary = service.inspectContent(local, objectManifest(absent, damaged), "content", backup) { rows.add(it) }
        assertEquals(listOf(PostgresBackupContentStatus.MISSING, PostgresBackupContentStatus.CORRUPT), rows.map { it.status })
        assertEquals(1L, summary.counts.getValue(PostgresBackupContentStatus.MISSING))
        assertEquals(1L, summary.counts.getValue(PostgresBackupContentStatus.CORRUPT))
        assertEquals(opened, closed)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `content inspection rejects invalid artifacts sources and store selections before asset reads`(): Unit = runBlocking {
        val ref = reference()
        val selected = withReferences(ref)
        val objects = objectManifest()
        assertFailsWith<IllegalArgumentException> { service.inspectContent(local, objects.copy(sourceId = "other"), "content", backup) }
        assertFailsWith<IllegalArgumentException> { service.inspectContent(local, objects, " ", backup) }
        assertFailsWith<IllegalArgumentException> { service.inspectContent(local, objects.copy(version = 2), "content", backup) }
        local.resolve("manifest.json").writeText(encoded(manifest))
        assertFailsWith<IllegalArgumentException> { service.inspectContent(local, objects, "content", backup) }
        local.resolve("manifest.json").writeText(encoded(selected))
        local.resolve(assertNotNull(selected.contentInventory).path).writeText("[]")
        assertFailsWith<BackupPayloadMismatchException> { service.inspectContent(local, objects, "content", backup) }
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `content inspection propagates backend failures cancellation and callback failures`(): Unit = runBlocking {
        val ref = reference()
        withReferences(ref)
        val entry = retainedEntry("metadata/${ref.contentId}/7/content", "assets/image")
        val objects = objectManifest(entry)
        val failed = IOException("storage unavailable")
        read = { throw failed }
        assertEquals(failed.message, assertFailsWith<IOException> { service.inspectContent(local, objects, "content", backup) }.message)
        val cancelled = CancellationException("inspection cancelled")
        read = { throw cancelled }
        assertEquals(cancelled.message, assertFailsWith<CancellationException> { service.inspectContent(local, objects, "content", backup) }.message)
        read = null
        retain(entry)
        val callback = IOException("report failed")
        assertEquals(callback.message, assertFailsWith<IOException> {
            service.inspectContent(local, objects, "content", backup) { throw callback }
        }.message)
        assertEquals(opened, closed)
        assertTrue(writes.isEmpty())
        assertFalse(storageClosed)
    }

    @Test
    fun `corrupt inventory writes cannot publish and a matching retry repairs only that file`(): Unit = runBlocking {
        val selected = withContentInventory()
        write = { path, stream, length ->
            if (path.toString().endsWith("content-references.json")) {
                storage.setInputStream(path, "{}".byteInputStream(), 2)
            } else storage.setInputStream(path, stream, length)
        }
        assertFailsWith<BackupPayloadMismatchException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(completed))
        write = null
        writes.clear()
        assertEquals(selected, service.upload(local, backup, prefix))
        assertEquals(listOf("$prefix/content-references.json", "$prefix/manifest.json"), writes)
    }

    @Test
    fun `short reported inventory writes fail publication and retry reuses verified inventory bytes`(): Unit = runBlocking {
        val selected = withContentInventory()
        write = { path, stream, length ->
            storage.setInputStream(path, stream, length).let { written ->
                if (path.toString().endsWith("content-references.json")) written - 1 else written
            }
        }
        assertFailsWith<IllegalStateException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(completed))
        write = null
        writes.clear()
        assertEquals(selected, service.upload(local, backup, prefix))
        assertEquals(listOf("$prefix/manifest.json"), writes)
    }

    @Test
    fun `inventory download cancellation removes staged components and preserves retained publication`(): Unit = runBlocking {
        withContentInventory()
        service.upload(local, backup, prefix)
        read = { path ->
            if (path.toString().endsWith("content-references.json")) {
                throw CancellationException("inventory download cancelled")
            }
            storage.getInputStream(path)
        }
        assertFailsWith<CancellationException> { service.download(backup, prefix, downloaded) }
        assertFalse(Files.exists(downloaded))
        assertTrue(Files.exists(completed))
        assertEquals(opened, closed)
    }

    @Test
    fun `valid inventory bytes with a mismatched retained record count cannot publish a download`(): Unit = runBlocking {
        val selected = withContentInventory()
        service.upload(local, backup, prefix)
        completed.writeText(encoded(selected.copy(contentInventory = assertNotNull(selected.contentInventory).copy(count = 1))))
        assertFailsWith<IllegalArgumentException> { service.download(backup, prefix, downloaded) }
        assertFalse(Files.exists(downloaded))
        assertTrue(Files.exists(completed))
        assertEquals(opened, closed)
    }

    @Test
    fun `content inventory round trips with its database and reuses verified retained files`(): Unit = runBlocking {
        val selected = withContentInventory()
        assertEquals(selected, service.upload(local, backup, prefix))
        val inventory = assertNotNull(selected.contentInventory)
        val retainedInventory = retained.resolve("$prefix/${inventory.path}")
        assertEquals("[]", retainedInventory.readText())
        writes.clear()
        assertEquals(selected, service.upload(local, backup, prefix))
        assertTrue(writes.isEmpty())
        local.toFile().deleteRecursively()
        assertEquals(selected, service.download(backup, prefix, downloaded))
        assertEquals("[]", downloaded.resolve(inventory.path).readText())
        assertEquals(selected, service.verify(downloaded))
        assertEquals(opened, closed)
    }

    @Test
    fun `interrupted content inventory transfer resumes without rewriting the verified database`(): Unit = runBlocking {
        val selected = withContentInventory()
        write = { path, stream, length ->
            if (path.toString().endsWith("content-references.json")) throw IOException("inventory interrupted")
            storage.setInputStream(path, stream, length)
        }
        assertFailsWith<IOException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(completed))
        assertEquals(selected, decode(intent))
        assertContentEquals(bytes, Files.readAllBytes(payload))
        write = null
        writes.clear()
        assertEquals(selected, service.upload(local, backup, prefix))
        assertEquals(listOf("$prefix/content-references.json", "$prefix/manifest.json"), writes)
    }

    @Test
    fun `unowned content inventory is rejected before writing any intent or database bytes`(): Unit = runBlocking {
        withContentInventory()
        val inventory = retained.resolve("$prefix/content-references.json")
        Files.createDirectories(inventory.parent)
        inventory.writeText("[]")
        assertFailsWith<IllegalArgumentException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
        assertFalse(Files.exists(intent))
        assertFalse(Files.exists(payload))
    }

    @Test
    fun `invalid local inventory count syntax bytes and links prevent storage writes`(): Unit = runBlocking {
        val selected = withContentInventory()
        val inventory = assertNotNull(selected.contentInventory)
        for (text in listOf("[ ", "{}", "[{}]", "[] trailing")) {
            withContentInventory(text)
            assertFailsWith<SerializationException> { service.upload(local, backup, prefix) }
            assertTrue(writes.isEmpty())
        }
        withContentInventory(count = 1)
        assertFailsWith<IllegalArgumentException> { service.verify(local) }
        withContentInventory()
        local.resolve(inventory.path).writeText("{}")
        assertFailsWith<BackupPayloadMismatchException> { service.verify(local) }
        val external = root.resolve("external.json").also { it.writeText("[]") }
        Files.delete(local.resolve(inventory.path))
        Files.createSymbolicLink(local.resolve(inventory.path), external)
        assertFailsWith<IllegalArgumentException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `missing or corrupt retained content inventory removes every unpublished download file`(): Unit = runBlocking {
        val selected = withContentInventory()
        val inventory = assertNotNull(selected.contentInventory)
        service.upload(local, backup, prefix)
        val saved = retained.resolve("$prefix/${inventory.path}")
        saved.writeText("{}")
        assertFailsWith<BackupPayloadMismatchException> { service.download(backup, prefix, downloaded) }
        assertFalse(Files.exists(downloaded))
        Files.delete(saved)
        assertFailsWith<bosca.storage.service.ObjectNotFoundException> { service.download(backup, prefix, downloaded) }
        assertFalse(Files.exists(downloaded))
        assertTrue(Files.exists(completed))
        assertContentEquals(bytes, Files.readAllBytes(payload))
        assertEquals(opened, closed)
    }

    @Test
    fun `verified native artifacts round trip through independent storage`(): Unit = runBlocking {
        assertEquals(manifest, service.upload(local, backup, prefix))
        assertEquals(listOf("$prefix/intent.json", "$prefix/database.dump", "$prefix/manifest.json"), writes)
        assertEquals(manifest, decode(intent))
        local.toFile().deleteRecursively()
        assertEquals(manifest, service.download(backup, prefix, downloaded))
        assertEquals(manifest, service.verify(downloaded))
        assertContentEquals(bytes, Files.readAllBytes(downloaded.resolve("database.dump")))
        assertEquals(setOf("database.dump", "manifest.json"), downloaded.toFile().list()?.toSet())
        assertEquals(opened, closed)
        assertFalse(storageClosed)
    }

    @Test
    fun `matching publication replay verifies bytes without rewriting them`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        writes.clear()
        assertEquals(manifest, service.upload(local, backup, prefix))
        assertTrue(writes.isEmpty())
        assertEquals(opened, closed)
    }

    @Test
    fun `different local intent cannot replace an existing backup`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        local.resolve("manifest.json").writeText(encoded(manifest.copy(id = "different")))
        writes.clear()
        assertFailsWith<IllegalArgumentException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
        assertEquals(manifest, decode(completed))
        assertContentEquals(bytes, Files.readAllBytes(payload))
    }

    @Test
    fun `unknown preexisting payload is never adopted or overwritten`(): Unit = runBlocking {
        Files.createDirectories(payload.parent)
        Files.write(payload, bytes)
        assertFailsWith<IllegalArgumentException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
        assertFalse(Files.exists(intent))
        assertFalse(Files.exists(completed))
    }

    @Test
    fun `partial payload retries preserve intent and repair only owned output`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("database.dump")) {
                assertEquals(manifest, decode(intent))
                assertFalse(Files.exists(completed))
                storage.setInputStream(path, stream.readNBytes(32).inputStream(), 32)
                throw IOException("interrupted payload")
            }
            storage.setInputStream(path, stream, length)
        }
        assertFailsWith<IOException> { service.upload(local, backup, prefix) }
        assertEquals(manifest, decode(intent))
        assertFalse(Files.exists(completed))
        write = null
        service = PostgresBackupServiceImpl(application, mockk(), mockk())
        assertEquals(manifest, service.upload(local, backup, prefix))
        assertEquals(2, writes.count { it.endsWith("database.dump") })
        assertContentEquals(bytes, Files.readAllBytes(payload))
    }

    @Test
    fun `partial completion manifest retries reuse the fully retained dump`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("manifest.json")) {
                storage.setInputStream(path, "{\"id\":".byteInputStream(), 6)
                6
            } else storage.setInputStream(path, stream, length)
        }
        assertFailsWith<IllegalStateException> { service.upload(local, backup, prefix) }
        write = null
        assertEquals(manifest, service.upload(local, backup, prefix))
        assertEquals(1, writes.count { it.endsWith("database.dump") })
        assertEquals(manifest, decode(completed))
    }

    @Test
    fun `short reported payload writes cannot publish but retry reuses verified output`(): Unit = runBlocking {
        write = { path, stream, length ->
            val written = storage.setInputStream(path, stream, length)
            if (path.toString().endsWith("database.dump")) written - 1 else written
        }
        assertFailsWith<IllegalStateException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(completed))
        write = null
        service.upload(local, backup, prefix)
        assertEquals(1, writes.count { it.endsWith("database.dump") })
    }

    @Test
    fun `unconsumed source streams cannot be acknowledged as uploaded`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("database.dump")) length ?: 0
            else storage.setInputStream(path, stream, length)
        }
        assertFailsWith<BackupPayloadMismatchException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(completed))
    }

    @Test
    fun `corrupt retained bytes prevent completion and can be repaired from the same local artifact`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("database.dump")) {
                val content = stream.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                storage.setInputStream(path, content.inputStream(), length)
            } else storage.setInputStream(path, stream, length)
        }
        assertFailsWith<BackupPayloadMismatchException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(completed))
        write = null
        service.upload(local, backup, prefix)
        assertContentEquals(bytes, Files.readAllBytes(payload))
    }

    @Test
    fun `matching completed artifact can repair missing retained bytes`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        Files.delete(payload)
        service.upload(local, backup, prefix)
        assertEquals(2, writes.count { it.endsWith("database.dump") })
        assertEquals(manifest, decode(completed))
    }

    @Test
    fun `a valid conflicting completion manifest cannot be replaced using intent`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        completed.writeText(encoded(manifest.copy(id = "someone-else")))
        writes.clear()
        assertFailsWith<IllegalArgumentException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
        assertEquals("someone-else", decode(completed).id)
    }

    @Test
    fun `completion writes must be read back and compared`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("manifest.json")) {
                stream.readBytes()
                val wrong = encoded(manifest.copy(id = "wrong")).toByteArray()
                storage.setInputStream(path, wrong.inputStream(), wrong.size.toLong())
                length ?: 0
            } else storage.setInputStream(path, stream, length)
        }
        assertFailsWith<IllegalStateException> { service.upload(local, backup, prefix) }
        assertEquals("wrong", decode(completed).id)
    }

    @Test
    fun `intent must be acknowledged and readable before payload writes`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("intent.json")) {
                stream.readBytes()
                0
            } else storage.setInputStream(path, stream, length)
        }
        assertFailsWith<IllegalStateException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(payload))
        assertFalse(Files.exists(completed))
        assertEquals(listOf("$prefix/intent.json"), writes)
    }

    @Test
    fun `malformed intent cannot authorize replacement of unknown payloads`(): Unit = runBlocking {
        Files.createDirectories(intent.parent)
        intent.writeText("{")
        Files.write(payload, bytes)
        assertFailsWith<SerializationException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
        assertContentEquals(bytes, Files.readAllBytes(payload))
    }

    @Test
    fun `changed source bytes during upload prevent completion`(): Unit = runBlocking {
        write = { path, stream, length ->
            val written = storage.setInputStream(path, stream, length)
            if (path.toString().endsWith("intent.json")) Files.write(local.resolve("database.dump"), bytes + 1.toByte())
            written
        }
        assertFailsWith<BackupPayloadMismatchException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(completed))
    }

    @Test
    fun `invalid local payload or destination prefix fails before remote access`(): Unit = runBlocking {
        for (invalid in listOf("", "/absolute", "../parent", "a/../b", "a//b", "a/./b", "a/")) {
            assertFailsWith<IllegalArgumentException> { service.upload(local, backup, invalid) }
            assertFailsWith<IllegalArgumentException> { service.download(backup, invalid, downloaded) }
        }
        Files.write(local.resolve("database.dump"), bytes.copyOf(bytes.size - 1))
        assertFailsWith<BackupPayloadMismatchException> { service.upload(local, backup, prefix) }
        assertTrue(reads.isEmpty() && stats.isEmpty() && writes.isEmpty())
        assertFalse(Files.exists(downloaded))
    }

    @Test
    fun `cancelled upload preserves retry intent and caller storage ownership`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("database.dump")) {
                throw CancellationException("upload cancelled")
            }
            storage.setInputStream(path, stream, length)
        }
        assertFailsWith<CancellationException> { service.upload(local, backup, prefix) }
        assertEquals(manifest, decode(intent))
        assertFalse(Files.exists(completed))
        assertFalse(storageClosed)
        write = null
        assertEquals(manifest, service.upload(local, backup, prefix))
    }

    @Test
    fun `download requires a completed manifest and never accepts intent as completion`(): Unit = runBlocking {
        Files.createDirectories(intent.parent)
        intent.writeText(encoded())
        Files.write(payload, bytes)
        assertFailsWith<bosca.storage.service.ObjectNotFoundException> { service.download(backup, prefix, downloaded) }
        assertFalse(Files.exists(downloaded))
        assertEquals(listOf("$prefix/manifest.json"), reads)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `corrupt truncated and oversized retained payloads cannot produce local artifacts`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        val changed = bytes.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for (bad in listOf(changed, bytes.copyOf(bytes.size - 1), bytes + 1.toByte())) {
            Files.write(payload, bad)
            assertFailsWith<BackupPayloadMismatchException> { service.download(backup, prefix, downloaded) }
            assertFalse(Files.exists(downloaded))
        }
        assertEquals(opened, closed)
        assertEquals(manifest, decode(completed))
    }

    @Test
    fun `unsupported metadata and missing payloads fail without creating completed local output`(): Unit = runBlocking {
        Files.createDirectories(completed.parent)
        for (bad in listOf(manifest.copy(version = 2), manifest.copy(payloadPath = "../database.dump"))) {
            completed.writeText(encoded(bad))
            assertFailsWith<IllegalArgumentException> { service.download(backup, prefix, downloaded) }
            assertFalse(Files.exists(downloaded))
        }
        completed.writeText(encoded())
        assertFailsWith<bosca.storage.service.ObjectNotFoundException> { service.download(backup, prefix, downloaded) }
        assertFalse(Files.exists(downloaded))
        assertEquals(opened, closed)
    }

    @Test
    fun `existing local output is never touched and requires no remote reads`(): Unit = runBlocking {
        Files.createDirectory(downloaded)
        downloaded.resolve("keep").writeText("original")
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { service.download(backup, prefix, downloaded) }
        assertEquals("original", downloaded.resolve("keep").readText())
        assertTrue(reads.isEmpty() && stats.isEmpty() && writes.isEmpty())
    }

    @Test
    fun `metadata backend failures are never treated as missing completion`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        writes.clear()
        read = { path ->
            if (path.toString().endsWith("manifest.json")) throw IOException("completion unavailable")
            storage.getInputStream(path)
        }
        val failure = assertFailsWith<IOException> { service.upload(local, backup, prefix) }
        assertEquals("completion unavailable", failure.message)
        assertTrue(writes.isEmpty())
        assertEquals(manifest, decode(completed))
    }

    @Test
    fun `backend serialization failures are not mistaken for partially written JSON`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        writes.clear()
        reads.clear()
        read = { path ->
            if (path.toString().endsWith("manifest.json")) throw SerializationException("backend response failed")
            storage.getInputStream(path)
        }
        val failure = assertFailsWith<SerializationException> { service.upload(local, backup, prefix) }
        assertEquals("backend response failed", failure.message)
        assertTrue(writes.isEmpty())
        assertEquals(listOf("$prefix/manifest.json"), reads)
    }

    @Test
    fun `unsupported completed formats cannot be repaired using matching intent`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        completed.writeText(encoded(manifest.copy(version = 2)))
        writes.clear()
        assertFailsWith<IllegalArgumentException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
        assertEquals(2, decode(completed).version)
    }

    @Test
    fun `a matching digest without a native header is insufficient for download`(): Unit = runBlocking {
        Files.createDirectories(completed.parent)
        val invalid = "not a PostgreSQL archive".toByteArray()
        Files.write(payload, invalid)
        completed.writeText(encoded(manifest.copy(
            length = invalid.size.toLong(), sha256 = MessageDigest.getInstance("SHA-256").digest(invalid).toHexString(),
        )))
        assertFailsWith<IllegalStateException> { service.download(backup, prefix, downloaded) }
        assertFalse(Files.exists(downloaded))
        assertEquals(opened, closed)
    }

    @Test
    fun `download cancellation and backend failures close streams remove partial files and preserve retained data`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        val savedManifest = completed.readText()
        for (failure in listOf(CancellationException("cancelled read"), IOException("backend failed"))) {
            read = { path ->
                val actual = storage.getInputStream(path)
                if (path.toString().endsWith("database.dump")) object : FilterInputStream(actual) {
                    private var count = 0
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (count > 0) throw failure
                        return super.read(buffer, offset, minOf(length, 32)).also { count += it }
                    }
                } else actual
            }
            if (failure is CancellationException) assertFailsWith<CancellationException> { service.download(backup, prefix, downloaded) }
            else assertFailsWith<IOException> { service.download(backup, prefix, downloaded) }
            assertFalse(Files.exists(downloaded))
            assertContentEquals(bytes, Files.readAllBytes(payload))
            assertEquals(savedManifest, completed.readText())
        }
        assertEquals(opened, closed)
        assertFalse(storageClosed)
    }

    private fun encoded(value: PostgresBackupManifest = manifest) = Json.encodeToString(PostgresBackupManifest.serializer(), value)
    private fun decode(path: Path) = Json.decodeFromString(PostgresBackupManifest.serializer(), path.readText())
}
