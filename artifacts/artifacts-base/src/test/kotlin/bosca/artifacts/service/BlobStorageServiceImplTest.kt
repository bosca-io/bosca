package bosca.artifacts.service

import bosca.artifacts.model.ArtifactBlob
import bosca.artifacts.model.ArtifactBlobPath
import bosca.artifacts.model.UploadSessionPath
import bosca.artifacts.repository.BlobRepository
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlobStorageServiceImplTest {

    private val blobRepository = mockk<BlobRepository>()
    private val objectStorage = mockk<ObjectStorageService>()
    private val service = BlobStorageServiceImpl(objectStorage, blobRepository)

    private val digest = "sha256:abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
    private val size = 1024L
    private val now = OffsetDateTime.now()
    private val blob = ArtifactBlob(digest = digest, size = size, refCount = 0, created = now)

    // -- store: new blob --

    @Test
    fun `store inserts new blob and writes to object storage`() = runTest {
        val input = ByteArrayInputStream(ByteArray(size.toInt()))
        coEvery { blobRepository.insert(digest, size, null) } returns blob
        coEvery { objectStorage.setInputStream(any<ArtifactBlobPath>(), any(), any()) } returns size

        val result = service.store(digest, input, size)

        assertEquals(blob, result)
        coVerify(exactly = 1) { blobRepository.insert(digest, size, null) }
        coVerify(exactly = 1) { objectStorage.setInputStream(any<ArtifactBlobPath>(), input, size) }
        coVerify(exactly = 0) { blobRepository.getByDigest(any()) }
    }

    // -- store: existing blob --

    @Test
    fun `store with existing blob skips object storage write`() = runTest {
        val input = ByteArrayInputStream(ByteArray(size.toInt()))
        val existingBlob = blob.copy(refCount = 3)
        coEvery { blobRepository.insert(digest, size, null) } returns null
        coEvery { blobRepository.getByDigest(digest) } returns existingBlob

        val result = service.store(digest, input, size)

        assertEquals(existingBlob, result)
        coVerify(exactly = 1) { blobRepository.insert(digest, size, null) }
        coVerify(exactly = 1) { blobRepository.getByDigest(digest) }
        coVerify(exactly = 0) { objectStorage.setInputStream(any(), any(), any()) }
    }

    // -- store: object storage failure with successful cleanup --

    @Test
    fun `store cleans up DB row when object storage write fails`() = runTest {
        val input = ByteArrayInputStream(ByteArray(size.toInt()))
        val storageError = RuntimeException("storage unavailable")
        coEvery { blobRepository.insert(digest, size, null) } returns blob
        coEvery { objectStorage.setInputStream(any<ArtifactBlobPath>(), any(), any()) } throws storageError
        coEvery { blobRepository.delete(digest) } returns Unit

        val thrown = assertFailsWith<RuntimeException> {
            service.store(digest, input, size)
        }

        assertEquals(storageError, thrown)
        coVerify(exactly = 1) { blobRepository.delete(digest) }
    }

    // -- store: object storage failure AND cleanup failure --

    @Test
    fun `store throws IllegalStateException with both errors when cleanup also fails`() = runTest {
        val input = ByteArrayInputStream(ByteArray(size.toInt()))
        val storageError = RuntimeException("storage unavailable")
        val cleanupError = RuntimeException("cleanup failed")
        coEvery { blobRepository.insert(digest, size, null) } returns blob
        coEvery { objectStorage.setInputStream(any<ArtifactBlobPath>(), any(), any()) } throws storageError
        coEvery { blobRepository.delete(digest) } throws cleanupError

        val thrown = assertFailsWith<IllegalStateException> {
            service.store(digest, input, size)
        }

        // The wrapper carries the original storage error as its cause
        assertEquals(storageError, thrown.cause)
        // The cleanup error is attached as a suppressed exception
        assertTrue(thrown.suppressed.any { it === cleanupError })
        coVerify(exactly = 1) { blobRepository.delete(digest) }
    }

    @Test
    fun `completeMultipartUpload completes storage before publishing blob metadata`() = runTest {
        val path = UploadSessionPath("session")
        val completedBlob = blob.copy(storagePath = path.toString())
        coEvery { blobRepository.findByDigest(digest) } returns null
        coEvery { objectStorage.completeMultipartUpload(path, "upload", 2, size) } returns Unit
        coEvery { blobRepository.insert(digest, size, path.toString()) } returns completedBlob

        assertEquals(completedBlob, service.completeMultipartUpload(digest, path, "upload", 2, size))

        coVerifyOrder {
            objectStorage.completeMultipartUpload(path, "upload", 2, size)
            blobRepository.insert(digest, size, path.toString())
        }
    }

    @Test
    fun `completeMultipartUpload aborts redundant upload when digest already exists`() = runTest {
        val path = UploadSessionPath("session")
        coEvery { blobRepository.findByDigest(digest) } returns blob
        coEvery { objectStorage.abortMultipartUpload(path, "upload", 2) } returns Unit

        assertEquals(blob, service.completeMultipartUpload(digest, path, "upload", 2, size))

        coVerify(exactly = 1) { objectStorage.abortMultipartUpload(path, "upload", 2) }
        coVerify(exactly = 0) { objectStorage.completeMultipartUpload(any(), any(), any(), any()) }
        coVerify(exactly = 0) { blobRepository.insert(any(), any(), any()) }
    }

    @Test
    fun `completeMultipartUpload aborts storage when completion fails`() = runTest {
        val path = UploadSessionPath("session")
        val failure = IllegalStateException("completion failed")
        coEvery { blobRepository.findByDigest(digest) } returns null
        coEvery { objectStorage.completeMultipartUpload(path, "upload", 2, size) } throws failure
        coEvery { objectStorage.abortMultipartUpload(path, "upload", 2) } returns Unit

        assertEquals(
            failure,
            assertFailsWith<IllegalStateException> {
                service.completeMultipartUpload(digest, path, "upload", 2, size)
            },
        )

        coVerify(exactly = 1) { objectStorage.abortMultipartUpload(path, "upload", 2) }
        coVerify(exactly = 0) { blobRepository.insert(any(), any(), any()) }
    }

    @Test
    fun `completeMultipartUpload deletes redundant completed object after concurrent insert`() = runTest {
        val path = UploadSessionPath("session")
        val existing = blob.copy(storagePath = "artifacts/blobs/existing")
        coEvery { blobRepository.findByDigest(digest) } returns null
        coEvery { objectStorage.completeMultipartUpload(path, "upload", 2, size) } returns Unit
        coEvery { blobRepository.insert(digest, size, path.toString()) } returns null
        coEvery { blobRepository.getByDigest(digest) } returns existing
        coEvery { objectStorage.delete(path) } returns Unit

        assertEquals(existing, service.completeMultipartUpload(digest, path, "upload", 2, size))

        coVerify(exactly = 1) { objectStorage.delete(path) }
    }

    // -- getInputStream: blob not found --

    @Test
    fun `getInputStream throws NoSuchElementException when blob does not exist`() = runTest {
        coEvery { blobRepository.findByDigest(digest) } returns null

        assertFailsWith<NoSuchElementException> {
            service.getInputStream(digest)
        }

        coVerify(exactly = 0) { objectStorage.getInputStream(any()) }
    }

    // -- getInputStream: blob exists --

    @Test
    fun `getInputStream returns stream from object storage when blob exists`() = runTest {
        val expectedStream = ByteArrayInputStream(ByteArray(10))
        coEvery { blobRepository.findByDigest(digest) } returns blob
        coEvery { objectStorage.getInputStream(any<ArtifactBlobPath>()) } returns expectedStream

        val result = service.getInputStream(digest)

        assertEquals(expectedStream, result)
        coVerify(exactly = 1) { objectStorage.getInputStream(any<ArtifactBlobPath>()) }
    }

    @Test
    fun `getInputStream uses an explicitly stored multipart path`() = runTest {
        val expectedStream = ByteArrayInputStream(ByteArray(10))
        val multipartBlob = blob.copy(storagePath = "artifacts/uploads/session")
        coEvery { blobRepository.findByDigest(digest) } returns multipartBlob
        coEvery { objectStorage.getInputStream(match { it.toString() == multipartBlob.storagePath }) } returns expectedStream

        assertEquals(expectedStream, service.getInputStream(digest))
    }

    // -- deleteIfUnreferenced: blob has references --

    @Test
    fun `deleteIfUnreferenced returns false when blob has references`() = runTest {
        coEvery { blobRepository.deleteIfUnreferenced(digest) } returns null

        val result = service.deleteIfUnreferenced(digest)

        assertFalse(result)
        coVerify(exactly = 0) { objectStorage.delete(any()) }
    }

    // -- deleteIfUnreferenced: blob unreferenced --

    @Test
    fun `deleteIfUnreferenced deletes from storage and returns true when unreferenced`() = runTest {
        coEvery { blobRepository.deleteIfUnreferenced(digest) } returns blob
        coEvery { objectStorage.delete(any<ArtifactBlobPath>()) } returns Unit

        val result = service.deleteIfUnreferenced(digest)

        assertTrue(result)
        coVerify(exactly = 1) { objectStorage.delete(any<ArtifactBlobPath>()) }
    }

    // -- deleteIfUnreferenced: object storage delete fails --

    @Test
    fun `deleteIfUnreferenced returns false and re-inserts DB record when object storage delete fails`() = runTest {
        coEvery { blobRepository.deleteIfUnreferenced(digest) } returns blob
        coEvery { objectStorage.delete(any<ArtifactBlobPath>()) } throws RuntimeException("storage delete failed")
        coEvery { blobRepository.insert(digest, blob.size, null) } returns blob

        val result = service.deleteIfUnreferenced(digest)

        assertFalse(result)
        coVerify(exactly = 1) { objectStorage.delete(any<ArtifactBlobPath>()) }
        coVerify(exactly = 1) { blobRepository.insert(digest, blob.size, null) }
    }

    // -- exists --

    @Test
    fun `exists returns true when blob is found`() = runTest {
        coEvery { blobRepository.findByDigest(digest) } returns blob

        assertTrue(service.exists(digest))
    }

    @Test
    fun `exists returns false when blob is not found`() = runTest {
        coEvery { blobRepository.findByDigest(digest) } returns null

        assertFalse(service.exists(digest))
    }

    // -- get --

    @Test
    fun `get returns blob when found`() = runTest {
        coEvery { blobRepository.findByDigest(digest) } returns blob

        assertEquals(blob, service.get(digest))
    }

    @Test
    fun `get returns null when not found`() = runTest {
        coEvery { blobRepository.findByDigest(digest) } returns null

        assertNull(service.get(digest))
    }

    // -- incrementRefCount / decrementRefCount --

    @Test
    fun `incrementRefCount delegates to repository`() = runTest {
        coEvery { blobRepository.incrementRefCount(digest) } returns blob.copy(refCount = 1)

        service.incrementRefCount(digest)

        coVerify(exactly = 1) { blobRepository.incrementRefCount(digest) }
    }

    @Test
    fun `decrementRefCount delegates to repository`() = runTest {
        coEvery { blobRepository.decrementRefCount(digest) } returns blob.copy(refCount = -1)

        service.decrementRefCount(digest)

        coVerify(exactly = 1) { blobRepository.decrementRefCount(digest) }
    }

    // -- delete --

    @Test
    fun `delete removes blob metadata and its digest-derived storage object`() = runTest {
        coEvery { blobRepository.findByDigest(digest) } returns blob
        coEvery { objectStorage.delete(any<ArtifactBlobPath>()) } returns Unit
        coEvery { blobRepository.delete(digest) } returns Unit

        service.delete(digest)

        coVerify(exactly = 1) { objectStorage.delete(any<ArtifactBlobPath>()) }
        coVerify(exactly = 1) { blobRepository.delete(digest) }
    }
}
