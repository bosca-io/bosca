package bosca.backup

import bosca.storage.service.FileSystemObjectStorageService
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Derive verified retained payloads from selected revisions without a precomputed source digest. */
class ObjectBackupCaptureTest {
    private lateinit var directory: Path
    private lateinit var retained: ObjectStorageService
    private lateinit var source: ObjectStorageService
    private lateinit var service: ObjectBackupCaptureService
    private lateinit var closed: AtomicBoolean
    private val bytes = "captured content 🖼".toByteArray()
    private val payload = "payloads/capture-1/asset"
    private val selected = ObjectStorageEntry("asset", ObjectStorageInfo(bytes.size.toLong(), version = "old"))

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("bosca-object-capture-")
        retained = FileSystemObjectStorageService("", "", mockk(), directory.toString(), mockk())
        source = mockk()
        service = ObjectBackupCaptureServiceImpl(mockk(), Json)
        closed = AtomicBoolean()
        coEvery { source.getInputStream(any(), "old") } answers { stream(bytes) }
    }

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `derives a manifest-ready digest and verifies retained bytes using the exact selected version`() = runTest {
        val result = capture()
        assertEquals(BackupObject("content", "asset", "old", bytes.size.toLong(), sha256(bytes), payload), result)
        result.validate()
        assertEquals(bytes.decodeToString(), retained.getString(StringObjectPath(payload)))
        assertTrue(closed.get())
        coVerify(exactly = 1) { source.getInputStream(match { it.toString() == "asset" }, "old") }
        coVerify(exactly = 0) { source.getInputStream(any()) }
        coVerify(exactly = 0) { source.getObjectInfo(any()) }
        verify(exactly = 0) { source.close() }
    }

    @Test
    fun `streams a large body with bounded source reads`() = runTest {
        val large = ByteArray(1024 * 1024 + 37) { (it % 251).toByte() }
        coEvery { source.getInputStream(any(), "old") } returns object : ByteArrayInputStream(large) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= DEFAULT_BUFFER_SIZE, "Capture must not request an asset-sized buffer")
                return super.read(buffer, offset, length)
            }
        }
        val result = capture(entry = selected.copy(info = selected.info.copy(length = large.size.toLong())))
        assertEquals(sha256(large), result.sha256)
        assertEquals(large.size.toLong(), result.length)
    }

    @Test
    fun `invalid capture intent fails before reading or writing any storage`() = runTest {
        val destination = mockk<ObjectStorageService>()
        for ((store, entry, path) in listOf(
            Triple(" ", selected, payload),
            Triple("content", selected.copy(key = " "), payload),
            Triple("content", selected.copy(key = "../asset"), payload),
            Triple("content", selected.copy(key = "/asset"), payload),
            Triple("content", selected.copy(info = selected.info.copy(version = null)), payload),
            Triple("content", selected.copy(info = selected.info.copy(version = " ")), payload),
            Triple("content", selected, ""),
            Triple("content", selected, "../asset"),
            Triple("content", selected, "/asset"),
            Triple("content", selected, "payloads//asset"),
        )) {
            assertFailsWith<IllegalArgumentException> {
                service.capture(store, entry, source, destination, path)
            }
        }
        coVerify(exactly = 0) { source.getInputStream(any(), any<String>()) }
        coVerify(exactly = 0) { destination.getObjectInfo(any()) }
        coVerify(exactly = 0) { destination.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `capture rejects the same source and destination service before storage access`() = runTest {
        assertFailsWith<IllegalArgumentException> { capture(target = source) }
        coVerify(exactly = 0) { source.getObjectInfo(any()) }
        coVerify(exactly = 0) { source.getInputStream(any(), any<String>()) }
    }

    @Test
    fun `an existing destination is preserved without reading source bytes`() = runTest {
        write(payload, "already retained")
        assertFailsWith<IllegalStateException> { capture() }
        assertEquals("already retained", retained.getString(StringObjectPath(payload)))
        coVerify(exactly = 0) { source.getInputStream(any(), any<String>()) }
    }

    @Test
    fun `destination lookup failures propagate before the source is opened`() = runTest {
        val failure = IOException("retained store denied")
        val target = object : ObjectStorageService by retained {
            override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo = throw failure
        }
        assertOriginalFailure(failure, assertFailsWith<IOException> { capture(target = target) })
        coVerify(exactly = 0) { source.getInputStream(any(), any<String>()) }
    }

    @Test
    fun `a missing retained source version never falls back or starts a destination write`() = runTest {
        val failure = ObjectNotFoundException(StringObjectPath("asset"))
        coEvery { source.getInputStream(any(), "old") } throws failure
        var deletes = 0
        val target = object : ObjectStorageService by retained {
            override suspend fun delete(path: ObjectPath) { deletes++ }
        }
        assertSame(failure, assertFailsWith<ObjectNotFoundException> { capture(target = target) })
        assertMissing()
        assertEquals(0, deletes)
        coVerify(exactly = 0) { source.getInputStream(any()) }
    }

    @Test
    fun `short and long source bodies cannot produce manifest entries and their partial outputs are removed`() = runTest {
        for (length in listOf(bytes.size - 1L, bytes.size + 1L, Long.MAX_VALUE)) {
            assertFailsWith<BackupPayloadMismatchException> {
                capture(entry = selected.copy(info = selected.info.copy(length = length)))
            }
            assertMissing()
            assertTrue(closed.get())
        }
    }

    @Test
    fun `a writer stopping early cannot acknowledge a successful capture`() = runTest {
        val target = object : ObjectStorageService by retained {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                write(path.toString(), "partial")
                stream.read()
                return selected.info.length
            }
        }
        assertFailsWith<BackupPayloadMismatchException> { capture(target = target) }
        assertMissing()
        assertTrue(closed.get())
    }

    @Test
    fun `an incorrect write count cannot acknowledge a successful capture`() = runTest {
        val target = object : ObjectStorageService by retained {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long =
                retained.setInputStream(path, stream, length) - 1
        }
        assertFailsWith<BackupPayloadMismatchException> { capture(target = target) }
        assertMissing()
    }

    @Test
    fun `acknowledged corrupt output fails read-back verification and is removed`() = runTest {
        val target = object : ObjectStorageService by retained {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                val copied = retained.setInputStream(path, stream, length)
                write(path.toString(), "x".repeat(bytes.size))
                return copied
            }
        }
        assertFailsWith<BackupPayloadMismatchException> { capture(target = target) }
        assertMissing()
    }

    @Test
    fun `a source read failure closes its stream and removes attempted output`() = runTest {
        val failure = IOException("source read failed")
        coEvery { source.getInputStream(any(), "old") } returns object : FilterInputStream(stream(bytes)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
        }
        assertOriginalFailure(failure, assertFailsWith<IOException> { capture() })
        assertMissing()
        assertTrue(closed.get())
    }

    @Test
    fun `a failed destination write retains its failure and removes its partial output`() = runTest {
        val failure = IOException("write failed")
        val target = object : ObjectStorageService by retained {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                write(path.toString(), "partial")
                throw failure
            }
        }
        assertOriginalFailure(failure, assertFailsWith<IOException> { capture(target = target) })
        assertMissing()
        assertTrue(closed.get())
    }

    @Test
    fun `source cancellation propagates while cleaning newly attempted output`() = runTest {
        val cancellation = CancellationException("capture cancelled")
        coEvery { source.getInputStream(any(), "old") } returns object : FilterInputStream(stream(bytes)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw cancellation
        }
        val observed = assertFailsWith<CancellationException> { capture() }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === cancellation })
        assertMissing()
        assertTrue(closed.get())
    }

    @Test
    fun `a read-back storage failure cannot acknowledge a retained entry`() = runTest {
        val failure = IOException("retained read failed")
        val target = object : ObjectStorageService by retained {
            override suspend fun getInputStream(path: ObjectPath): InputStream = throw failure
        }
        assertOriginalFailure(failure, assertFailsWith<IOException> { capture(target = target) })
        assertMissing()
    }

    @Test
    fun `cleanup failure remains attached to the original capture failure`() = runTest {
        val failure = IOException("write failed")
        val cleanup = IOException("delete failed")
        val target = object : ObjectStorageService by retained {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long = throw failure
            override suspend fun delete(path: ObjectPath) = throw cleanup
        }
        val observed = assertFailsWith<IOException> { capture(target = target) }
        assertOriginalFailure(failure, observed)
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.flatMap { it.suppressed.asSequence() }.any { suppressed ->
            generateSequence(suppressed) { it.cause }.any { it === cleanup }
        })
        assertTrue(closed.get())
    }

    @Test
    fun `a source close failure prevents acknowledgement and removes the output`() = runTest {
        val failure = IOException("source close failed")
        coEvery { source.getInputStream(any(), "old") } returns object : ByteArrayInputStream(bytes) {
            override fun close() { throw failure }
        }
        assertOriginalFailure(failure, assertFailsWith<IOException> { capture() })
        assertMissing()
    }

    @Test
    fun `a silently unsuccessful delete is reported alongside the original capture failure`() = runTest {
        val failure = IOException("write failed")
        val target = object : ObjectStorageService by retained {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                write(path.toString(), "partial")
                throw failure
            }
            override suspend fun delete(path: ObjectPath) = Unit
        }
        val observed = assertFailsWith<IOException> { capture(target = target) }
        assertOriginalFailure(failure, observed)
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { cause ->
            cause.suppressed.any { it is IllegalStateException && it.message.orEmpty().contains("cleanup") }
        })
        assertEquals("partial", retained.getString(StringObjectPath(payload)))
    }

    private fun assertOriginalFailure(expected: Throwable, observed: Throwable) {
        assertEquals(expected.message, observed.message)
        assertTrue(generateSequence(observed) { it.cause }.any { it === expected })
    }

    private suspend fun capture(
        entry: ObjectStorageEntry = selected,
        target: ObjectStorageService = retained,
    ) = service.capture("content", entry, source, target, payload)

    private fun stream(content: ByteArray): InputStream = object : ByteArrayInputStream(content) {
        override fun close() { closed.set(true) }
    }

    private suspend fun write(key: String, content: String) {
        val data = content.toByteArray()
        retained.setInputStream(StringObjectPath(key), data.inputStream(), data.size.toLong())
    }

    private suspend fun assertMissing() {
        assertFailsWith<ObjectNotFoundException> { retained.getObjectInfo(StringObjectPath(payload)) }
    }

    private fun sha256(content: ByteArray) = MessageDigest.getInstance("SHA-256").digest(content).toHexString()
}
