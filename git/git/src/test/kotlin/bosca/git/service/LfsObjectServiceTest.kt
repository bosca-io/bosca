package bosca.git.service

import bosca.git.model.LfsObject
import bosca.git.model.LfsUploadValidationException
import bosca.git.repository.LfsObjectRepository
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.test.*

class LfsObjectServiceTest {
    private val repository = mockk<LfsObjectRepository>()
    private val storage = mockk<ObjectStorageService>(relaxed = true)
    private val service = LfsObjectServiceImpl(repository, storage)
    private val repositoryId = UUID.random()
    private val data = "LFS object".toByteArray()
    private val oid = digest(data)

    @BeforeTest
    fun setup() {
        coEvery { repository.findByOid(any(), any()) } returns null
        coEvery { repository.create(any()) } answers { firstArg<LfsObject>().copy(id = UUID.random()) }
        coEvery { storage.createMultipartUpload(any()) } returns "upload-id"
        coEvery { storage.uploadMultipartPart(any(), any(), any(), any(), any()) } answers {
            arg<InputStream>(3).transferTo(OutputStream.nullOutputStream())
        }
        coEvery { storage.setInputStream(any(), any(), any()) } answers {
            secondArg<InputStream>().transferTo(OutputStream.nullOutputStream())
        }
    }

    @Test
    fun `metadata lookup and recording preserve repository identity`() = runTest {
        assertNull(service.findByOid(repositoryId, oid))
        val obj = service.recordUpload(repositoryId, oid, data.size.toLong())
        assertNotNull(obj)
        coEvery { repository.findByOid(repositoryId, oid) } returns obj
        assertEquals(obj, service.findByOid(repositoryId, oid))
        assertEquals("git-lfs/$repositoryId/$oid", service.getStoragePath(repositoryId, oid).toString())
    }

    @Test
    fun `total size treats missing aggregate as zero`() = runTest {
        coEvery { repository.getTotalSize(repositoryId) } returnsMany listOf(null, 10240L)
        assertEquals(0L, service.getTotalSize(repositoryId))
        assertEquals(10240L, service.getTotalSize(repositoryId))
    }

    @Test
    fun `upload verifies bytes and persists its unique completed path`() = runTest {
        val obj = service.upload(repositoryId, oid, data.inputStream(), data.size.toLong())
        assertTrue(obj.storagePath.startsWith("git-lfs/$repositoryId/$oid/"))
        coVerifyOrder {
            storage.createMultipartUpload(any())
            storage.uploadMultipartPart(any(), "upload-id", 1, any(), data.size.toLong())
            storage.completeMultipartUpload(match { it.toString() == obj.storagePath }, "upload-id", 1, data.size.toLong())
            repository.create(match { it.storagePath == obj.storagePath })
        }
        coVerify(exactly = 0) { storage.delete(any()) }
    }

    @Test
    fun `large upload uses bounded consecutive parts`() = runTest {
        val large = ByteArray(8 * 1024 * 1024 + 17) { (it % 251).toByte() }
        service.upload(repositoryId, digest(large), large.inputStream(), large.size.toLong())
        coVerifyOrder {
            storage.uploadMultipartPart(any(), any(), 1, any(), 8L * 1024 * 1024)
            storage.uploadMultipartPart(any(), any(), 2, any(), 17L)
            storage.completeMultipartUpload(any(), any(), 2, large.size.toLong())
        }
    }

    @Test
    fun `empty content is verified without creating a multipart upload`() = runTest {
        val empty = ByteArray(0)
        val obj = service.upload(repositoryId, digest(empty), empty.inputStream(), 0)
        assertEquals(0, obj.size)
        coVerify { storage.setInputStream(any(), any(), 0) }
        coVerify(exactly = 0) { storage.createMultipartUpload(any()) }
    }

    @Test
    fun `hash and length mismatches abort storage without recording metadata`() = runTest {
        for ((expectedOid, size) in listOf("a".repeat(64) to data.size.toLong(), oid to data.size + 1L, oid to data.size - 1L)) {
            assertFailsWith<LfsUploadValidationException> {
                service.upload(repositoryId, expectedOid, data.inputStream(), size)
            }
        }
        coVerify(exactly = 3) { storage.abortMultipartUpload(any(), "upload-id", any()) }
        coVerify(exactly = 3) { storage.delete(any()) }
        coVerify(exactly = 0) { storage.completeMultipartUpload(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.create(any()) }
    }

    @Test
    fun `invalid oid and negative size fail before allocating storage`() = runTest {
        for ((expectedOid, size) in listOf("../bad" to 1L, "z".repeat(64) to 1L, oid to -1L)) {
            assertFailsWith<LfsUploadValidationException> {
                service.upload(repositoryId, expectedOid, data.inputStream(), size)
            }
        }
        coVerify(exactly = 0) { storage.createMultipartUpload(any()) }
    }

    @Test
    fun `producer byte count must match the consumed bytes`() = runTest {
        assertFailsWith<LfsUploadValidationException> {
            service.upload(repositoryId, oid, data.size.toLong()) { output -> output.write(data); 0L }
        }
        coVerify { storage.abortMultipartUpload(any(), any(), 1) }
    }

    @Test
    fun `short storage writes cannot complete an object`() = runTest {
        coEvery { storage.uploadMultipartPart(any(), any(), any(), any(), any()) } returns 1L
        assertFailsWith<IllegalStateException> { service.upload(repositoryId, oid, data.inputStream(), data.size.toLong()) }
        coVerify { storage.abortMultipartUpload(any(), any(), 1) }
        coVerify(exactly = 0) { repository.create(any()) }
    }

    @Test
    fun `completion and database failures remove only the new upload`() = runTest {
        val failure = IOException("storage failed")
        coEvery { storage.completeMultipartUpload(any(), any(), any(), any()) } throws failure
        assertEquals(failure.message, assertFailsWith<IOException> { service.upload(repositoryId, oid, data.inputStream(), data.size.toLong()) }.message)
        coEvery { storage.completeMultipartUpload(any(), any(), any(), any()) } returns Unit
        coEvery { repository.create(any()) } throws failure
        assertEquals(failure.message, assertFailsWith<IOException> { service.upload(repositoryId, oid, data.inputStream(), data.size.toLong()) }.message)
        coVerify(exactly = 2) { storage.delete(match { it.toString().startsWith("git-lfs/$repositoryId/$oid/") }) }
    }

    @Test
    fun `concurrent duplicate upload returns the existing object and removes redundant bytes`() = runTest {
        val existing = LfsObject(repositoryId = repositoryId, oid = oid, size = data.size.toLong(), storagePath = "winner")
        coEvery { repository.create(any()) } returns null
        coEvery { repository.findByOid(repositoryId, oid) } returns existing
        assertEquals(existing, service.upload(repositoryId, oid, data.inputStream(), data.size.toLong()))
        coVerify { storage.delete(match { it.toString() != "winner" }) }
    }

    @Test
    fun `missing or inconsistent duplicate metadata fails loudly and cleans the redundant object`() = runTest {
        coEvery { repository.create(any()) } returns null
        assertFailsWith<IllegalStateException> { service.upload(repositoryId, oid, data.inputStream(), data.size.toLong()) }
        coEvery { repository.findByOid(repositoryId, oid) } returns LfsObject(repositoryId = repositoryId, oid = oid, size = 1)
        assertFailsWith<IllegalStateException> { service.upload(repositoryId, oid, data.inputStream(), data.size.toLong()) }
        coVerify(exactly = 2) { storage.delete(any()) }
    }

    @Test
    fun `cancellation remains cancellation even when cleanup fails`() = runTest {
        val failure = CancellationException("request cancelled")
        val abortFailure = IOException("abort failed")
        val deleteFailure = IOException("delete failed")
        coEvery { storage.abortMultipartUpload(any(), any(), any()) } throws abortFailure
        coEvery { storage.delete(any()) } throws deleteFailure
        val actual = assertFailsWith<CancellationException> {
            service.upload(repositoryId, oid, data.size.toLong()) { throw failure }
        }
        assertEquals(failure.message, actual.message)
        coVerify { storage.abortMultipartUpload(any(), any(), any()) }
        coVerify { storage.delete(any()) }
        coVerify(exactly = 0) { repository.create(any()) }
    }

    @Test
    fun `completion failures retain cleanup errors as suppressed exceptions`() = runTest {
        coEvery { storage.completeMultipartUpload(any(), any(), any(), any()) } throws IOException("completion failed")
        coEvery { storage.abortMultipartUpload(any(), any(), any()) } throws IOException("abort failed")
        coEvery { storage.delete(any()) } throws IOException("delete failed")
        val failure = assertFailsWith<IOException> { service.upload(repositoryId, oid, data.inputStream(), data.size.toLong()) }
        assertEquals("completion failed", failure.message)
        val cleanupMessages = generateSequence(failure as Throwable) { it.cause }
            .flatMap { it.suppressed.asSequence() }.map { it.message }.toList()
        assertTrue("abort failed" in cleanupMessages)
        assertTrue("delete failed" in cleanupMessages)
    }

    @Test
    fun `storage failure unblocks a body larger than the pipe buffer`() = runTest {
        val failure = IOException("part failed")
        coEvery { storage.uploadMultipartPart(any(), any(), any(), any(), any()) } throws failure
        val large = ByteArray(20 * 1024 * 1024)
        assertEquals(failure.message, assertFailsWith<IOException> {
            service.upload(repositoryId, digest(large), large.inputStream(), large.size.toLong())
        }.message)
    }

    @Test
    fun `empty upload failures remove the unique path without aborting multipart storage`() = runTest {
        coEvery { storage.setInputStream(any(), any(), any()) } returns 1L
        assertFailsWith<IllegalStateException> {
            service.upload(repositoryId, digest(ByteArray(0)), InputStream.nullInputStream(), 0)
        }
        coVerify { storage.delete(any()) }
        coVerify(exactly = 0) { storage.abortMultipartUpload(any(), any(), any()) }
    }

    @Test
    fun `download resolves the recorded path and rejects missing metadata`() = runTest {
        assertFailsWith<NoSuchElementException> { service.download(repositoryId, oid) }
        coEvery { repository.findByOid(repositoryId, oid) } returns LfsObject(repositoryId = repositoryId, oid = oid, size = data.size.toLong(), storagePath = "recorded-path")
        coEvery { storage.getInputStream(any()) } returns data.inputStream()
        assertContentEquals(data, service.download(repositoryId, oid).use { it.readBytes() })
        coVerify { storage.getInputStream(match { it.toString() == "recorded-path" }) }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
