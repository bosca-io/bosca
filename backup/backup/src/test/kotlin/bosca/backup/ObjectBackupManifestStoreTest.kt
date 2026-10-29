package bosca.backup

import bosca.storage.service.ManagedFileSystemObjectStorageService
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectPath
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.StringObjectPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObjectBackupManifestStoreTest {
    private lateinit var directory: Path
    private lateinit var storage: ManagedFileSystemObjectStorageService
    private lateinit var store: ObjectBackupManifestStore
    private val path = StringObjectPath("manifests/backup-1.json")
    private val data = "data".toByteArray()
    private val revision = BackupObject(
        "content", "metadata/item/1/content", "revision-1", 4,
        "3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7", "objects/data",
    )
    private val manifest = ObjectBackupManifest("backup-1", "source-1", "content", "2026-10-15T00:00:00Z", listOf(revision))

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("bosca-object-backup-")
        storage = ManagedFileSystemObjectStorageService("", "", mockk(), directory.toString(), mockk())
        store = ObjectBackupManifestStore(storage, Json)
    }

    @AfterTest
    fun tearDown() { directory.toFile().deleteRecursively() }

    @Test
    fun `verified inventory is saved and loaded using real object storage`() = runTest {
        writePayload(data)
        store.save(path, manifest)

        assertEquals(manifest, store.load(path))
        assertTrue(directory.resolve(path.toString()).exists())
    }

    @Test
    fun `missing payload prevents publication`() = runTest {
        assertFailsWith<ObjectNotFoundException> { store.save(path, manifest) }
        assertNotPublished()
    }

    @Test
    fun `wrong byte length prevents publication`() = runTest {
        writePayload("short".toByteArray())
        assertFailsWith<IllegalStateException> { store.save(path, manifest) }
        assertNotPublished()
    }

    @Test
    fun `same length corruption prevents publication`() = runTest {
        writePayload("xxxx".toByteArray())
        assertFailsWith<IllegalStateException> { store.save(path, manifest) }
        assertNotPublished()
    }

    @Test
    fun `valid empty payload has a verified digest`() = runTest {
        writePayload(ByteArray(0))
        val empty = manifest.copy(objects = listOf(revision.copy(length = 0, sha256 = digest(ByteArray(0)))))
        store.save(path, empty)
        assertEquals(empty, store.load(path))
    }

    @Test
    fun `invalid inventory prevents reads and publication`() = runTest {
        val invalid = manifest.copy(objects = listOf(revision.copy(sha256 = "not-a-digest")))
        assertFailsWith<IllegalArgumentException> { store.save(path, invalid) }
        assertNotPublished()
    }

    @Test
    fun `manifest path cannot overwrite a referenced payload`() = runTest {
        writePayload(data)
        assertFailsWith<IllegalArgumentException> { store.save(StringObjectPath(revision.payloadPath), manifest) }
        assertEquals("data", storage.getString(StringObjectPath(revision.payloadPath)))
    }

    @Test
    fun `invalid manifest destination fails before storage access`() = runTest {
        val backend = mockk<ManagedObjectStorageService>(relaxed = true)
        assertFailsWith<IllegalArgumentException> {
            ObjectBackupManifestStore(backend, Json).save(StringObjectPath("../manifest.json"), manifest)
        }
        coVerify(exactly = 0) { backend.getInputStream(any()) }
        coVerify(exactly = 0) { backend.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `loading validates the artifact metadata`() = runTest {
        val invalid = Json.encodeToString(ObjectBackupManifest.serializer(), manifest.copy(version = 2))
        storage.setInputStream(path, invalid.byteInputStream(), invalid.toByteArray().size.toLong())

        assertFailsWith<IllegalArgumentException> { store.load(path) }
    }

    @Test
    fun `loading an inventory does not download object payloads`() = runTest {
        val backend = mockk<ManagedObjectStorageService>()
        coEvery { backend.getInputStream(path) } returns Json.encodeToString(ObjectBackupManifest.serializer(), manifest).byteInputStream()

        assertEquals(manifest, ObjectBackupManifestStore(backend, Json).load(path))
        coVerify(exactly = 1) { backend.getInputStream(any()) }
    }

    @Test
    fun `shared payload is verified once`() = runTest {
        val backend = mockk<ManagedObjectStorageService>()
        coEvery { backend.getInputStream(any()) } answers { data.inputStream() }
        val shared = manifest.copy(objects = listOf(revision, revision.copy(key = "other", revision = "revision-2")))

        ObjectBackupManifestStore(backend, Json).verify(shared)

        coVerify(exactly = 1) { backend.getInputStream(any()) }
    }

    @Test
    fun `verification detects corruption after publication`() = runTest {
        writePayload(data)
        store.save(path, manifest)
        writePayload("xxxx".toByteArray())

        assertFailsWith<IllegalStateException> { store.verify(store.load(path)) }
    }

    @Test
    fun `cancelled payload read does not publish a manifest`() = runTest {
        val backend = mockk<ManagedObjectStorageService>(relaxed = true)
        coEvery { backend.getInputStream(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { ObjectBackupManifestStore(backend, Json).save(path, manifest) }
        coVerify(exactly = 0) { backend.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `verification streams bounded chunks and closes its input`() = runTest {
        val bytes = ByteArray(65537) { (it % 251).toByte() }
        var closed = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= 8192)
                return super.read(buffer, offset, length)
            }
            override fun close() { closed = true; super.close() }
        }
        val backend = mockk<ManagedObjectStorageService>()
        coEvery { backend.getInputStream(any()) } returns input
        val large = manifest.copy(objects = listOf(revision.copy(length = bytes.size.toLong(), sha256 = digest(bytes))))

        ObjectBackupManifestStore(backend, Json).verify(large)

        assertTrue(closed)
    }

    @Test
    fun `payload read failure prevents publication`() = runTest {
        val backend = mockk<ManagedObjectStorageService>(relaxed = true)
        coEvery { backend.getInputStream(any()) } returns object : InputStream() {
            override fun read(): Int = throw IOException("Read failed")
        }
        assertFailsWith<IOException> { ObjectBackupManifestStore(backend, Json).save(path, manifest) }
        coVerify(exactly = 0) { backend.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `short manifest write cannot report success`() = runTest {
        val backend = mockk<ManagedObjectStorageService>()
        coEvery { backend.getInputStream(any()) } answers { data.inputStream() }
        coEvery { backend.setInputStream(any(), any(), any()) } returns 0

        assertFailsWith<IllegalStateException> { ObjectBackupManifestStore(backend, Json).save(path, manifest) }
    }

    @Test
    fun `an acknowledged manifest write must match its persisted inventory`() = runTest {
        writePayload(data)
        val backend = object : ManagedObjectStorageService by storage {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                val consumed = stream.readBytes()
                val different = manifest.copy(id = "different")
                val replacement = Json.encodeToString(ObjectBackupManifest.serializer(), different).toByteArray()
                storage.setInputStream(path, replacement.inputStream(), replacement.size.toLong())
                return consumed.size.toLong()
            }
        }
        assertFailsWith<IllegalStateException> { ObjectBackupManifestStore(backend, Json).save(path, manifest) }
    }

    private suspend fun writePayload(bytes: ByteArray) {
        storage.setInputStream(StringObjectPath(revision.payloadPath), bytes.inputStream(), bytes.size.toLong())
    }

    private fun assertNotPublished() { assertFalse(Files.exists(directory.resolve(path.toString()))) }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
}
