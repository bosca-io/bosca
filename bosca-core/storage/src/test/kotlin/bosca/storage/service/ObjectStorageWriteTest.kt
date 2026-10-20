package bosca.storage.service

import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListPartsRequest
import software.amazon.awssdk.services.s3.model.ListPartsResponse
import software.amazon.awssdk.services.s3.model.Part
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.model.UploadPartResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ObjectStorageWriteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val path = StringObjectPath("asset")

    @Test
    fun `S3 streamed upload reports actual bytes without a current object lookup`() = runTest {
        val client = s3Client("written-version", "written-etag")
        val caller = Thread.currentThread()
        var writer: Thread? = null
        var count = 0L
        every { client.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) } answers {
            writer = Thread.currentThread()
            val bytes = secondArg<RequestBody>().contentStreamProvider().newStream().use { it.readBytes() }
            count += bytes.size
            assertEquals(bytes.size.toLong(), firstArg<UploadPartRequest>().contentLength())
            UploadPartResponse.builder().eTag("part").build()
        }
        val source = ByteArray(6_500_003) { it.toByte() }
        var closed = false
        val input = object : ByteArrayInputStream(source) {
            override fun close() { closed = true; super.close() }
        }
        val written = s3(client).setInputStream(path, input)
        assertEquals(source.size.toLong(), written)
        assertEquals(source.size.toLong(), count)
        assertNotSame(caller, writer)
        assertFalse(closed)
        verify(exactly = 2) { client.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) }
        verify(exactly = 0) { client.headObject(any<HeadObjectRequest>()) }
        verify(exactly = 0) { client.abortMultipartUpload(any<AbortMultipartUploadRequest>()) }
    }

    @Test
    fun `S3 explicit multipart completion submits the selected uploaded parts`() = runTest {
        val client = s3Client("selected-version", "selected-etag")
        s3(client).completeMultipartUpload(path, "upload", 1, 4)
        verify(exactly = 1) {
            client.completeMultipartUpload(match<CompleteMultipartUploadRequest> {
                it.key() == "asset" && it.uploadId() == "upload" &&
                    it.multipartUpload().parts().single().eTag() == "part"
            })
        }
        verify(exactly = 0) { client.headObject(any<HeadObjectRequest>()) }
    }

    @Test
    fun `S3 writes do not require provider versioning`() = runTest {
        for (version in listOf(null, "", " ", "null")) {
            val client = s3Client(version, " ")
            assertEquals(4L, s3(client).setInputStream(path, "data".byteInputStream(), 4))
            s3(client).completeMultipartUpload(path, "upload", 1, 4)
            verify(exactly = 0) { client.headObject(any<HeadObjectRequest>()) }
        }
    }

    @Test
    fun `S3 stream failure keeps its original error and abort failure`() = runTest {
        val client = s3Client("written", "etag")
        val failure = IOException("input failed")
        val cleanup = IOException("abort failed")
        every { client.abortMultipartUpload(any<AbortMultipartUploadRequest>()) } throws cleanup
        val input = object : InputStream() { override fun read(): Int = throw failure }
        val observed = assertFailsWith<IOException> { s3(client).setInputStream(path, input) }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { cause ->
            cause.suppressed.any { it === cleanup }
        })
        verify(exactly = 0) { client.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) }
    }

    @Test
    fun `S3 abort reporting the original failure cannot mask it with self suppression`() = runTest {
        val client = s3Client(null, null)
        val failure = IOException("storage unavailable")
        every { client.abortMultipartUpload(any<AbortMultipartUploadRequest>()) } throws failure
        val input = object : InputStream() { override fun read(): Int = throw failure }
        val observed = assertFailsWith<IOException> { s3(client).setInputStream(path, input) }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
        verify(exactly = 0) { client.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) }
    }

    @Test
    fun `S3 cancellation aborts and propagates without a successful result`() = runTest {
        val client = s3Client("written", "etag")
        val failure = CancellationException("upload cancelled")
        every { client.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) } throws failure
        val observed = assertFailsWith<CancellationException> {
            s3(client).setInputStream(path, "data".byteInputStream())
        }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
        verify(exactly = 1) { client.abortMultipartUpload(any<AbortMultipartUploadRequest>()) }
        verify(exactly = 0) { client.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) }
    }

    @Test
    fun `S3 native finalization errors leave explicit upload ownership with the caller`() = runTest {
        val client = s3Client("written", "etag")
        val failure = S3Exception.builder().statusCode(503).message("completion failed").build()
        every { client.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) } throws failure
        assertSame(failure, assertFailsWith<S3Exception> {
            s3(client).completeMultipartUpload(path, "upload", 1, 4)
        })
        verify(exactly = 0) { client.abortMultipartUpload(any<AbortMultipartUploadRequest>()) }
    }

    @Test
    fun `Google uploads preserve content settings and caller stream ownership`() = runTest {
        val client = mockk<Storage>()
        val existing = mockk<Blob>()
        every { client.get(BlobId.of("bucket", "asset")) } returns existing
        val written = googleBlob(4)
        val caller = Thread.currentThread()
        var writer: Thread? = null
        every { client.createFrom(existing, any<InputStream>(), any<Int>(), *anyVararg()) } answers {
            writer = Thread.currentThread()
            assertEquals("data", secondArg<InputStream>().readBytes().decodeToString())
            written
        }
        var closed = false
        val input = object : ByteArrayInputStream("data".toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(4L, google(client).setInputStream(path, input))
        assertNotSame(caller, writer)
        assertFalse(closed)
        verify(exactly = 1) { client.get(BlobId.of("bucket", "asset")) }
    }

    @Test
    fun `Google new object upload reports consumed bytes without another lookup`() = runTest {
        val client = googleClient(googleBlob(4))
        assertEquals(4L, google(client).setInputStream(path, "data".byteInputStream(), 4))
        verify(exactly = 1) { client.get(BlobId.of("bucket", "asset")) }
    }

    @Test
    fun `Google rejects response sizes different from the bytes actually consumed`() = runTest {
        for (size in listOf(null, 3L, 5L)) {
            val client = googleClient(googleBlob(size))
            assertFailsWith<IllegalStateException> {
                google(client).setInputStream(path, "data".byteInputStream())
            }
        }
    }

    @Test
    fun `Google backend write failures and cancellation propagate without another metadata lookup`() = runTest {
        for (failure in listOf(IOException("stream failed"), StorageException(503, "upload failed"), CancellationException("cancelled"))) {
            val client = googleClient(googleBlob(4))
            every { client.createFrom(any<BlobInfo>(), any<InputStream>(), any<Int>(), *anyVararg()) } throws failure
            val observed = assertFailsWith<Exception> { google(client).setInputStream(path, "data".byteInputStream()) }
            assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
            verify(exactly = 1) { client.get(BlobId.of("bucket", "asset")) }
        }
    }

    @Test
    fun `filesystem upload reports actual bytes without a provider revision`() = runTest {
        val service = filesystem()
        assertEquals(4L, service.setInputStream(path, "data".byteInputStream()))
        assertEquals("data", service.getString(path))
        assertNull(service.getObjectInfo(path).version)
    }

    @Test
    fun `default multipart completion writes all bytes and removes staged parts`() = runTest {
        val service = filesystem()
        val upload = service.createMultipartUpload(path)
        service.uploadMultipartPart(path, upload, 1, "da".byteInputStream(), 2)
        service.uploadMultipartPart(path, upload, 2, "ta".byteInputStream(), 2)
        service.completeMultipartUpload(path, upload, 2, 4)
        assertEquals("data", service.getString(path))
        assertTrue(temporaryFolder.root.walkTopDown().filter { it.isFile }.map { it.name }.toList() == listOf("asset"))
    }

    @Test
    fun `default multipart size failure cleans parts without replacing existing content`() = runTest {
        val service = filesystem()
        service.setInputStream(path, "original".byteInputStream())
        val upload = service.createMultipartUpload(path)
        service.uploadMultipartPart(path, upload, 1, "data".byteInputStream(), 4)
        assertFailsWith<IllegalStateException> { service.completeMultipartUpload(path, upload, 1, 5) }
        assertEquals("original", service.getString(path))
        assertEquals(listOf("asset"), temporaryFolder.root.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    @Test
    fun `default multipart preserves write failure when part cleanup also fails`() = runTest {
        val service = spyk(filesystem())
        val upload = service.createMultipartUpload(path)
        service.uploadMultipartPart(path, upload, 1, "data".byteInputStream(), 4)
        val failure = IOException("final write failed")
        val cleanup = IOException("part cleanup failed")
        coEvery { service.setInputStream(path, any(), any()) } throws failure
        coEvery { service.abortMultipartUpload(path, upload, 1) } throws cleanup
        val observed = assertFailsWith<IOException> {
            service.completeMultipartUpload(path, upload, 1, 4)
        }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { cause ->
            cause.suppressed.any { it === cleanup }
        })
    }

    @Test
    fun `default multipart cleanup failure prevents a successful result after bytes were written`() = runTest {
        val service = spyk(filesystem())
        val upload = service.createMultipartUpload(path)
        service.uploadMultipartPart(path, upload, 1, "data".byteInputStream(), 4)
        val cleanup = IOException("part cleanup failed")
        coEvery { service.abortMultipartUpload(path, upload, 1) } throws cleanup
        val observed = assertFailsWith<IOException> {
            service.completeMultipartUpload(path, upload, 1, 4)
        }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === cleanup })
        assertEquals("data", service.getString(path))
    }

    @Test
    fun `cancelling default multipart removes staged parts and preserves existing content`() = runTest {
        val service = spyk(filesystem())
        service.setInputStream(path, "original".byteInputStream())
        val upload = service.createMultipartUpload(path)
        service.uploadMultipartPart(path, upload, 1, "data".byteInputStream(), 4)
        val writing = CompletableDeferred<Unit>()
        coEvery { service.setInputStream(path, any(), any()) } coAnswers {
            writing.complete(Unit)
            awaitCancellation()
        }
        val work = async { service.completeMultipartUpload(path, upload, 1, 4) }
        writing.await()
        work.cancel()
        assertFailsWith<CancellationException> { work.await() }
        work.join()
        coVerify(exactly = 1) { service.abortMultipartUpload(path, upload, 1) }
        assertEquals("original", service.getString(path))
        assertEquals(listOf("asset"), temporaryFolder.root.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    private fun s3Client(version: String?, etag: String?): S3Client = mockk<S3Client>().also { client ->
        every { client.createMultipartUpload(any<CreateMultipartUploadRequest>()) } returns
            CreateMultipartUploadResponse.builder().uploadId("upload").build()
        every { client.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) } returns
            UploadPartResponse.builder().eTag("part").build()
        every { client.listParts(any<ListPartsRequest>()) } returns ListPartsResponse.builder()
            .parts(Part.builder().partNumber(1).size(4L).eTag("part").build()).isTruncated(false).build()
        every { client.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) } returns
            CompleteMultipartUploadResponse.builder().versionId(version).eTag(etag).build()
        every { client.abortMultipartUpload(any<AbortMultipartUploadRequest>()) } returns
            AbortMultipartUploadResponse.builder().build()
    }

    private fun googleBlob(size: Long?) = mockk<Blob> {
        every { this@mockk.size } returns size
    }

    private fun googleClient(written: Blob) = mockk<Storage>().also { client ->
        every { client.get(BlobId.of("bucket", "asset")) } returns null
        every { client.createFrom(any<BlobInfo>(), any<InputStream>(), any<Int>(), *anyVararg()) } answers {
            secondArg<InputStream>().readBytes()
            written
        }
    }

    private fun s3(client: S3Client) = S3ObjectStorageService("bucket", client, "", "", mockk(), mockk())
    private fun google(client: Storage) = GoogleObjectStorageService("bucket", client, "", "", mockk(), mockk())
    private fun filesystem() = FileSystemObjectStorageService("", "", mockk(), temporaryFolder.root.path, mockk())
}
