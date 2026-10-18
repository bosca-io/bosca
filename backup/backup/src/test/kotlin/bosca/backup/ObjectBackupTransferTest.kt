package bosca.backup

import bosca.storage.service.FileSystemObjectStorageService
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ObjectBackupTransferTest {
    private lateinit var directory: Path
    private lateinit var source: FileSystemObjectStorageService
    private lateinit var destination: FileSystemObjectStorageService
    private lateinit var transfer: ObjectBackupTransfer
    private val data = "data".toByteArray()
    private val entry = BackupObject(
        store = "content",
        key = "metadata/item/1/content",
        revision = "revision-1",
        length = data.size.toLong(),
        sha256 = digest(data),
        payloadPath = "objects/revision-1",
    )
    private val sourcePath = StringObjectPath(entry.key)
    private val destinationPath = StringObjectPath(entry.payloadPath)

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("bosca-object-transfer-")
        source = storage("source")
        destination = storage("destination")
        transfer = ObjectBackupTransfer(mapOf("content" to source), destination)
    }

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `copies between independent stores using source and payload keys`() = runTest {
        writeSource(data)

        transfer.copy(entry)

        assertEquals("data", destination.getString(destinationPath))
        assertEquals("data", source.getString(sourcePath))
    }

    @Test
    fun `interrupted replay preserves a retained snapshot payload`() = runTest {
        destination.setInputStream(destinationPath, data.inputStream(), entry.length)
        val inventory = ObjectBackupManifest(
            id = "retained-snapshot",
            sourceId = "source-1",
            scope = "content",
            created = "2026-10-16T00:00:00Z",
            objects = listOf(entry),
        )
        val store = ObjectBackupManifestStore(destination, Json)
        val manifestPath = StringObjectPath("manifests/retained.json")
        store.save(manifestPath, inventory)

        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } answers {
            object : ByteArrayInputStream(data) {
                private var reads = 0

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ > 0) throw IOException("connection interrupted")
                    return super.read(buffer, offset, minOf(length, 2))
                }
            }
        }

        ObjectBackupTransfer(mapOf("content" to origin), destination).copy(entry)

        store.verify(store.load(manifestPath))
        assertEquals("data", destination.getString(destinationPath))
        coVerify(exactly = 0) { origin.getInputStream(any()) }
    }

    @Test
    fun `mismatching existing payloads are repaired from the declared revision`() = runTest {
        writeSource(data)
        for (bytes in listOf("dat", "xxxx", "dataa")) {
            destination.setInputStream(destinationPath, bytes.byteInputStream(), bytes.length.toLong())

            transfer.copy(entry)

            assertEquals("data", destination.getString(destinationPath))
        }
    }

    @Test
    fun `destination probe read failure cannot trigger an overwrite`() = runTest {
        val origin = mockk<ObjectStorageService>(relaxed = true)
        val target = mockk<ObjectStorageService>()
        val expected = IOException("destination unavailable")
        coEvery { target.getInputStream(any()) } throws expected

        val failure = assertFailsWith<IOException> {
            ObjectBackupTransfer(mapOf("content" to origin), target).copy(entry)
        }

        assertEquals(expected.message, failure.message)
        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `unrelated destination state failure cannot trigger an overwrite`() = runTest {
        val origin = mockk<ObjectStorageService>(relaxed = true)
        val target = mockk<ObjectStorageService>()
        val expected = IllegalStateException("storage not ready")
        coEvery { target.getInputStream(any()) } throws expected

        val failure = assertFailsWith<IllegalStateException> {
            ObjectBackupTransfer(mapOf("content" to origin), target).copy(entry)
        }

        assertEquals(expected.message, failure.message)
        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `cancelled destination probe cannot trigger an overwrite`() = runTest {
        val origin = mockk<ObjectStorageService>(relaxed = true)
        val target = mockk<ObjectStorageService>()
        coEvery { target.getInputStream(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            ObjectBackupTransfer(mapOf("content" to origin), target).copy(entry)
        }

        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `empty objects are persisted and verified`() = runTest {
        writeSource(ByteArray(0))

        transfer.copy(entry.copy(length = 0, sha256 = digest(ByteArray(0))))

        assertEquals("", destination.getString(destinationPath))
    }

    @Test
    fun `large payload crosses independent stores without truncation`() = runTest {
        val bytes = ByteArray(5 * 1024 * 1024 + 17) { (it % 251).toByte() }
        writeSource(bytes)

        transfer.copy(entry.copy(length = bytes.size.toLong(), sha256 = digest(bytes)))

        destination.getInputStream(destinationPath).use {
            assertContentEquals(bytes, it.readBytes())
        }
    }

    @Test
    fun `invalid declared revision fails before storage access`() = runTest {
        val origin = mockk<ObjectStorageService>(relaxed = true)
        val target = mockk<ObjectStorageService>(relaxed = true)

        assertFailsWith<IllegalArgumentException> {
            ObjectBackupTransfer(mapOf("content" to origin), target).copy(entry.copy(sha256 = "multipart-etag-2"))
        }

        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `missing source is an explicit failure`() = runTest {
        assertFailsWith<ObjectNotFoundException> {
            transfer.copy(entry)
        }
    }

    @Test
    fun `changed source bytes with the same length cannot count as copied`() = runTest {
        writeSource("xxxx".toByteArray())

        assertFailsWith<IllegalStateException> {
            transfer.copy(entry)
        }
    }

    @Test
    fun `truncated source cannot count as copied`() = runTest {
        writeSource("dat".toByteArray())

        assertFailsWith<IllegalStateException> {
            transfer.copy(entry)
        }
    }

    @Test
    fun `source longer than the declared revision cannot count as copied`() = runTest {
        writeSource("dataa".toByteArray())

        assertFailsWith<IllegalStateException> {
            transfer.copy(entry)
        }
    }

    @Test
    fun `a retry repairs an unfinished payload without affecting other revisions`() = runTest {
        val retainedPath = StringObjectPath("objects/retained-revision")
        destination.setInputStream(retainedPath, "retained".byteInputStream(), 8)
        writeSource("xxxx".toByteArray())
        assertFailsWith<IllegalStateException> {
            transfer.copy(entry)
        }

        writeSource(data)
        ObjectBackupTransfer(mapOf("content" to source), destination).copy(entry)

        assertEquals("data", destination.getString(destinationPath))
        assertEquals("retained", destination.getString(retainedPath))
    }

    @Test
    fun `acknowledged destination is read back and corruption fails`() = runTest {
        writeSource(data)
        for (bytes in listOf("xxxx", "dat", "dataa")) {
            val target = acknowledgingDestination(bytes.toByteArray())

            assertFailsWith<IllegalStateException> {
                ObjectBackupTransfer(mapOf("content" to source), target).copy(entry)
            }

            coVerify(exactly = 2) { target.getInputStream(match { it.toString() == entry.payloadPath }) }
        }
    }

    @Test
    fun `missing acknowledged destination cannot count as copied`() = runTest {
        writeSource(data)
        val target = acknowledgingDestination(data)
        coEvery { target.getInputStream(any()) } throws ObjectNotFoundException(destinationPath)

        assertFailsWith<ObjectNotFoundException> {
            ObjectBackupTransfer(mapOf("content" to source), target).copy(entry)
        }
    }

    @Test
    fun `destination read failure propagates`() = runTest {
        writeSource(data)
        val target = acknowledgingDestination(data)
        payloadAfterInitialMiss(target) {
            throw IOException("destination unavailable")
        }

        val failure = assertFailsWith<IOException> {
            ObjectBackupTransfer(mapOf("content" to source), target).copy(entry)
        }

        assertEquals("destination unavailable", failure.message)
    }

    @Test
    fun `short write acknowledgement cannot count as copied`() = runTest {
        writeSource(data)
        val target = acknowledgingDestination(data)
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            secondArg<InputStream>().readBytes()
            entry.length - 1
        }

        assertFailsWith<IllegalStateException> {
            ObjectBackupTransfer(mapOf("content" to source), target).copy(entry)
        }

        coVerify(exactly = 1) { target.getInputStream(any()) }
    }

    @Test
    fun `destination claiming success without consuming the source fails`() = runTest {
        writeSource(data)
        val target = acknowledgingDestination(data)
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            secondArg<InputStream>().read()
            entry.length
        }

        assertFailsWith<IllegalStateException> {
            ObjectBackupTransfer(mapOf("content" to source), target).copy(entry)
        }

        coVerify(exactly = 1) { target.getInputStream(any()) }
    }

    @Test
    fun `reading exactly the declared length still detects trailing source bytes`() = runTest {
        writeSource("dataa".toByteArray())
        val target = acknowledgingDestination(data)
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            secondArg<InputStream>().readNBytes(data.size)
            entry.length
        }

        assertFailsWith<IllegalStateException> {
            ObjectBackupTransfer(mapOf("content" to source), target).copy(entry)
        }
    }

    @Test
    fun `writers may stop after the declared length without reading EOF`() = runTest {
        writeSource(data)
        val target = acknowledgingDestination(data)
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            val input = secondArg<InputStream>()
            repeat(data.size) {
                assertEquals(data[it].toInt(), input.read())
            }
            entry.length
        }

        ObjectBackupTransfer(mapOf("content" to source), target).copy(entry)

        coVerify(exactly = 2) { target.getInputStream(any()) }
    }

    @Test
    fun `empty writers may acknowledge zero bytes without consuming the source`() = runTest {
        writeSource(ByteArray(0))
        val target = mockk<ObjectStorageService>()
        coEvery { target.setInputStream(any(), any(), any()) } returns 0
        payloadAfterInitialMiss(target) { ByteArray(0).inputStream() }

        ObjectBackupTransfer(mapOf("content" to source), target).copy(
            entry.copy(length = 0, sha256 = digest(ByteArray(0))),
        )

        coVerify(exactly = 2) { target.getInputStream(any()) }
    }

    @Test
    fun `source read sizes are bounded even when destination requests large buffers`() = runTest {
        val bytes = ByteArray(65537) { (it % 251).toByte() }
        var closed = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= 8192)
                return super.read(buffer, offset, length)
            }

            override fun close() {
                closed = true
                super.close()
            }
        }
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } returns input
        val target = mockk<ObjectStorageService>()
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            secondArg<InputStream>().copyTo(ByteArrayOutputStream(), 65536)
        }
        payloadAfterInitialMiss(target) { bytes.inputStream() }

        ObjectBackupTransfer(mapOf("content" to origin), target).copy(
            entry.copy(length = bytes.size.toLong(), sha256 = digest(bytes))
        )

        assertTrue(closed)
    }

    @Test
    fun `source stream closes when destination write fails`() = runTest {
        var closed = false
        val input = object : ByteArrayInputStream(data) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } returns input
        val target = mockk<ObjectStorageService>()
        coEvery { target.getInputStream(any()) } throws ObjectNotFoundException(destinationPath)
        coEvery { target.setInputStream(any(), any(), any()) } throws IOException("write failed")

        val failure = assertFailsWith<IOException> {
            ObjectBackupTransfer(mapOf("content" to origin), target).copy(entry)
        }

        assertEquals("write failed", failure.message)
        assertTrue(closed)
        coVerify(exactly = 1) { target.getInputStream(any()) }
    }

    @Test
    fun `source read failures propagate and close the stream`() = runTest {
        var closed = false
        val input = object : ByteArrayInputStream(data) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                throw IOException("source read failed")
            }

            override fun close() {
                closed = true
                super.close()
            }
        }
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } returns input

        assertFailsWith<IOException> {
            ObjectBackupTransfer(mapOf("content" to origin), destination).copy(entry)
        }

        assertTrue(closed)
    }

    @Test
    fun `cancellation opening the source propagates without a destination write`() = runTest {
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } throws CancellationException("cancelled")
        val target = mockk<ObjectStorageService>(relaxed = true)
        coEvery { target.getInputStream(any()) } throws ObjectNotFoundException(destinationPath)

        assertFailsWith<CancellationException> {
            ObjectBackupTransfer(mapOf("content" to origin), target).copy(entry)
        }

        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `cancellation during source reads stops the copy and closes the stream`() = runTest {
        var closed = false
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } coAnswers {
            val job = currentCoroutineContext().job
            object : ByteArrayInputStream(data) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    job.cancel()
                    return super.read(buffer, offset, length)
                }

                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }

        assertFailsWith<CancellationException> {
            ObjectBackupTransfer(mapOf("content" to origin), destination).copy(entry)
        }

        assertTrue(closed)
    }

    @Test
    fun `cancelled destination verification propagates and closes the stream`() = runTest {
        writeSource(data)
        var closed = false
        val target = acknowledgingDestination(data)
        payloadAfterInitialMiss(target) {
            object : ByteArrayInputStream(data) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    throw CancellationException("verification cancelled")
                }

                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }

        assertFailsWith<CancellationException> {
            ObjectBackupTransfer(mapOf("content" to source), target).copy(entry)
        }

        assertTrue(closed)
    }

    private fun storage(name: String): FileSystemObjectStorageService = FileSystemObjectStorageService(
        "", "", mockk(), directory.resolve(name).toString(), mockk()
    )

    private suspend fun writeSource(bytes: ByteArray) {
        source.setInputStream(sourcePath, bytes.inputStream(), bytes.size.toLong())
    }

    private fun acknowledgingDestination(bytes: ByteArray): ObjectStorageService {
        val target = mockk<ObjectStorageService>()
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            assertEquals(entry.payloadPath, firstArg<StringObjectPath>().toString())
            assertEquals(entry.length, thirdArg<Long?>())
            secondArg<InputStream>().readBytes().size.toLong()
        }
        payloadAfterInitialMiss(target) { bytes.inputStream() }
        return target
    }

    private fun payloadAfterInitialMiss(target: ObjectStorageService, open: () -> InputStream) {
        var reads = 0
        coEvery { target.getInputStream(any()) } answers {
            if (reads++ == 0) throw ObjectNotFoundException(destinationPath)
            open()
        }
    }

    @Test
    fun `version transfer selects the exact provider version when known bytes need repair`() = runTest {
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any(), "provider-1") } answers { data.inputStream() }
        ObjectBackupTransfer(mapOf(entry.store to origin), destination).copyVersion(entry, "provider-1")
        assertEquals("data", destination.getString(destinationPath))
        coVerify(exactly = 1) { origin.getInputStream(match { it.toString() == entry.key }, "provider-1") }
        coVerify(exactly = 0) { origin.getInputStream(any()) }
    }

    @Test
    fun `version transfer reuses known matching output without opening source content`() = runTest {
        val origin = mockk<ObjectStorageService>()
        destination.setInputStream(destinationPath, data.inputStream(), data.size.toLong())
        ObjectBackupTransfer(mapOf(entry.store to origin), destination).copyVersion(entry, "provider-1")
        coVerify(exactly = 0) { origin.getInputStream(any(), any<String>()) }
        coVerify(exactly = 0) { origin.getInputStream(any()) }
        assertEquals("data", destination.getString(destinationPath))
    }

    @Test
    fun `version transfer rejects blank versions before storage access`() = runTest {
        val origin = mockk<ObjectStorageService>()
        val target = mockk<ObjectStorageService>()
        assertFailsWith<IllegalArgumentException> {
            ObjectBackupTransfer(mapOf(entry.store to origin), target).copyVersion(entry, " ")
        }
        coVerify(exactly = 0) { target.getInputStream(any()) }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHexString()
}
