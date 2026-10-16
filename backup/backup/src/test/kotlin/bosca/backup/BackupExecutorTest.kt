package bosca.backup

import bosca.content.collection.model.CollectionSupplementary
import bosca.content.metadata.model.MetadataSupplementary
import bosca.di.annotation.InternalDI
import bosca.serialization.UUID
import bosca.storage.service.ObjectNotFoundException
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.io.InputStream
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class BackupExecutorTest {
    private lateinit var fixture: BackupExecutorFixture

    @BeforeTest
    fun setUp() { fixture = BackupExecutorFixture() }

    @AfterTest
    fun tearDown() = runBlocking { fixture.close() }

    @Test
    fun `uploaded empty primary files are included`() = runTest {
        val item = fixture.metadata(length = 0)
        fixture.metadata += item
        fixture.sourceFiles["metadata/${item.id}/${item.version}/content"] = { ByteArray(0).inputStream() }

        fixture.backup()

        val entries = fixture.archiveEntries()
        assertContentEquals(ByteArray(0), entries["files/metadata/${item.id}/${item.version}/content"])
        coVerify { fixture.repository.updateStatus(fixture.backupId, "completed") }
    }

    @Test
    fun `uploaded primary files with unknown lengths are included`() = runTest {
        val item = fixture.metadata(length = null)
        fixture.metadata += item
        fixture.sourceFiles["metadata/${item.id}/${item.version}/content"] = { "data".byteInputStream() }

        fixture.backup()

        assertContentEquals("data".toByteArray(), fixture.archiveEntries()["files/metadata/${item.id}/${item.version}/content"])
    }

    @Test
    fun `missing uploaded primary content fails the backup`() = runTest {
        fixture.metadata += fixture.metadata()

        assertFailsWith<ObjectNotFoundException> { fixture.backup() }

        verifyFailed()
    }

    @Test
    fun `failure while reading required primary bytes fails the backup`() = runTest {
        val item = fixture.metadata()
        val failure = IOException("Storage read interrupted")
        fixture.metadata += item
        fixture.sourceFiles["metadata/${item.id}/${item.version}/content"] = {
            object : InputStream() {
                private var first = true
                override fun read(): Int {
                    if (first) { first = false; return 1 }
                    throw failure
                }
            }
        }

        fixture.assertPropagated(failure, assertFailsWith<IOException> { fixture.backup() })

        verifyFailed()
    }

    @Test
    fun `missing uploaded metadata supplementary content fails the backup`() = runTest {
        val item = fixture.metadata(uploaded = false)
        val now = OffsetDateTime.now()
        fixture.metadata += item
        fixture.metadataSupplementary[item.id] = listOf(MetadataSupplementary(
            id = UUID.random(), metadataId = item.id, key = "thumbnail", name = "Thumbnail", created = now, modified = now, uploaded = now,
        ))

        assertFailsWith<ObjectNotFoundException> { fixture.backup() }

        verifyFailed()
    }

    @Test
    fun `missing uploaded collection supplementary content fails the backup`() = runTest {
        val item = fixture.collection()
        val now = OffsetDateTime.now()
        fixture.collections += item
        fixture.collectionSupplementary[item.id] = listOf(CollectionSupplementary(
            id = UUID.random(), collectionId = item.id, key = "thumbnail", name = "Thumbnail", created = now, modified = now, uploaded = now,
        ))

        assertFailsWith<ObjectNotFoundException> { fixture.backup() }

        verifyFailed()
    }

    @Test
    fun `absent optional collection primary content does not create a phantom file`() = runTest {
        val item = fixture.collection()
        fixture.collections += item

        fixture.backup()

        assertFalse(fixture.archiveEntries().containsKey("files/collection/${item.id}/content"))
        coVerify { fixture.repository.updateStatus(fixture.backupId, "completed") }
    }

    @Test
    fun `collection primary storage failure is not treated as absent content`() = runTest {
        val item = fixture.collection()
        val failure = IOException("Storage unavailable")
        fixture.collections += item
        fixture.sourceFiles["collection/${item.id}/content"] = { throw failure }

        fixture.assertPropagated(failure, assertFailsWith<IOException> { fixture.backup() })

        verifyFailed()
    }

    @Test
    fun `cancellation during required file read propagates without recording a failure`() = runTest {
        val item = fixture.metadata()
        val cancelled = CancellationException("Cancelled transfer")
        fixture.metadata += item
        fixture.sourceFiles["metadata/${item.id}/${item.version}/content"] = { throw cancelled }

        fixture.assertPropagated(cancelled, assertFailsWith<CancellationException> { fixture.backup() })

        verifyCancelled()
    }

    @Test
    fun `cancellation during optional collection read propagates`() = runTest {
        val item = fixture.collection()
        val cancelled = CancellationException("Cancelled collection transfer")
        fixture.collections += item
        fixture.sourceFiles["collection/${item.id}/content"] = { throw cancelled }

        fixture.assertPropagated(cancelled, assertFailsWith<CancellationException> { fixture.backup() })

        verifyCancelled()
    }

    @Test
    fun `unuploaded content and previous backup archives are excluded`() = runTest {
        fixture.metadata += fixture.metadata(uploaded = false)
        fixture.metadata += fixture.metadata(contentType = BACKUP_CONTENT_TYPE)

        fixture.backup()

        assertTrue(fixture.archiveEntries().keys.none { it.startsWith("files/") })
        coVerify(exactly = 0) { fixture.storage.getInputStream(any()) }
    }

    @Test
    fun `database-only backups do not enumerate or read assets`() = runTest {
        fixture.metadata += fixture.metadata()
        fixture.collections += fixture.collection()

        fixture.backup(includeFiles = false)

        assertEquals(BackupTables.ordered.size + 1, fixture.archiveEntries().size)
        coVerify(exactly = 0) { fixture.metadataService.getAll(any(), any()) }
        coVerify(exactly = 0) { fixture.collectionService.getAll(any(), any()) }
        coVerify(exactly = 0) { fixture.storage.getInputStream(any()) }
    }

    @Test
    fun `successful metadata and collection files preserve bytes`() = runTest {
        val metadata = fixture.metadata()
        val collection = fixture.collection()
        fixture.metadata += metadata
        fixture.collections += collection
        val paths = listOf("metadata/${metadata.id}/${metadata.version}/content", "collection/${collection.id}/content")
        for (path in paths) fixture.sourceFiles[path] = { "data".byteInputStream() }

        fixture.backup()

        val entries = fixture.archiveEntries()
        for (path in paths) assertContentEquals("data".toByteArray(), entries["files/$path"])
        assertTrue(entries.containsKey("manifest.json"))
        coVerify { fixture.repository.updateStatus(fixture.backupId, "completed") }
    }

    private fun verifyFailed() {
        coVerify(exactly = 1) { fixture.repository.setError(fixture.backupId, any()) }
        coVerify(exactly = 0) { fixture.repository.updateStatus(fixture.backupId, "completed") }
        coVerify(exactly = 0) { fixture.metadataService.add(any(), any(), any()) }
        assertTrue(fixture.writtenFiles.isEmpty())
    }

    private fun verifyCancelled() {
        coVerify(exactly = 0) { fixture.repository.setError(any(), any()) }
        coVerify(exactly = 0) { fixture.repository.updateStatus(fixture.backupId, "completed") }
        assertTrue(fixture.writtenFiles.isEmpty())
    }
}
