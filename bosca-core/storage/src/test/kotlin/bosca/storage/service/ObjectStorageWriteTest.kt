package bosca.storage.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

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
    fun `filesystem upload reports actual bytes without a provider revision`() = runTest {
        val service = filesystem()
        assertEquals(4L, service.setInputStream(path, "data".byteInputStream()))
        assertEquals("data", service.getString(path))
        assertNull(service.getObjectInfo(path).version)
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

    private fun s3(client: S3Client) = ManagedS3ObjectStorageService("bucket", client, "", "", mockk(), mockk())
    private fun filesystem() = ManagedFileSystemObjectStorageService("", "", mockk(), temporaryFolder.root.path, mockk())
}
