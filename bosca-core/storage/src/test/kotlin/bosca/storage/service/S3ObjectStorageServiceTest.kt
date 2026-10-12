package bosca.storage.service

import bosca.di.ObjectProvider
import bosca.security.service.SecurityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.reflect.KClass
import java.io.ByteArrayInputStream
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class ThrowingProvider<T : Any>(override val type: KClass<T>) : ObjectProvider<T> {
    override suspend fun get(): T {
        throw IllegalStateException("Not expected to be called in this test")
    }
}

class S3ObjectStorageServiceTest {

    @Test
    fun `explicit multipart lifecycle uploads and completes existing parts`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.createMultipartUpload(any<CreateMultipartUploadRequest>()) } returns
            CreateMultipartUploadResponse.builder().uploadId("upload-1").build()
        every { s3.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) } returns
            UploadPartResponse.builder().eTag("part-etag").build()
        every { s3.listParts(any<ListPartsRequest>()) } returns ListPartsResponse.builder()
            .parts(
                Part.builder().partNumber(1).eTag("etag-1").size(5L).build(),
                Part.builder().partNumber(2).eTag("etag-2").size(3L).build(),
            )
            .isTruncated(false)
            .build()
        every { s3.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) } returns
            CompleteMultipartUploadResponse.builder().build()
        val service = service(s3)
        val path = object : ObjectPath { override fun toString() = "multipart-key" }

        val uploadId = service.createMultipartUpload(path)
        val uploaded = service.uploadMultipartPart(path, uploadId, 1, ByteArrayInputStream(ByteArray(5)), 5)
        service.completeMultipartUpload(path, uploadId, 2, 8)

        assertEquals("upload-1", uploadId)
        assertEquals(5L, uploaded)
        verify(exactly = 1) {
            s3.uploadPart(
                match<UploadPartRequest> {
                    it.key() == "multipart-key" && it.uploadId() == "upload-1" &&
                        it.partNumber() == 1 && it.contentLength() == 5L
                },
                any<RequestBody>(),
            )
        }
        verify(exactly = 1) {
            s3.completeMultipartUpload(match<CompleteMultipartUploadRequest> {
                it.multipartUpload().parts().map(CompletedPart::partNumber) == listOf(1, 2)
            })
        }
    }

    @Test
    fun `explicit multipart completion sorts lexicographic parts across pages numerically`() = runTest {
        val s3 = mockk<S3Client>()
        val parts = (1..13).sortedBy { it.toString() }.map {
            Part.builder().partNumber(it).eTag("etag-$it").size(it.toLong()).build()
        }
        every { s3.listParts(any<ListPartsRequest>()) } returnsMany listOf(
            ListPartsResponse.builder().parts(parts.take(6))
                .isTruncated(true).nextPartNumberMarker(2).build(),
            ListPartsResponse.builder().parts(parts.drop(6)).isTruncated(false).build(),
        )
        every { s3.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) } returns
            CompleteMultipartUploadResponse.builder().build()
        val path = object : ObjectPath { override fun toString() = "multipart-key" }

        service(s3).completeMultipartUpload(path, "upload-1", 13, 91)

        verify(exactly = 1) {
            s3.listParts(match<ListPartsRequest> { it.partNumberMarker() == 2 })
        }
        verify(exactly = 1) {
            s3.completeMultipartUpload(match<CompleteMultipartUploadRequest> {
                it.multipartUpload().parts().map(CompletedPart::partNumber) == (1..13).toList() &&
                    it.multipartUpload().parts().map(CompletedPart::eTag) == (1..13).map { number -> "etag-$number" }
            })
        }
    }

    @Test
    fun `explicit multipart completion rejects duplicate parts`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.listParts(any<ListPartsRequest>()) } returns ListPartsResponse.builder()
            .parts(listOf(2, 1, 2).map {
                Part.builder().partNumber(it).eTag("etag-$it").size(5L).build()
            }).isTruncated(false).build()
        val path = object : ObjectPath { override fun toString() = "multipart-key" }

        assertFailsWith<IllegalArgumentException> {
            service(s3).completeMultipartUpload(path, "upload-1", 2, 15)
        }
        verify(exactly = 0) { s3.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) }
    }

    @Test
    fun `explicit multipart completion rejects incorrect total length after sorting`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.listParts(any<ListPartsRequest>()) } returns ListPartsResponse.builder()
            .parts(listOf(2, 1).map {
                Part.builder().partNumber(it).eTag("etag-$it").size(5L).build()
            }).isTruncated(false).build()
        val path = object : ObjectPath { override fun toString() = "multipart-key" }

        val error = assertFailsWith<IllegalArgumentException> {
            service(s3).completeMultipartUpload(path, "upload-1", 2, 11)
        }
        assertEquals("multipart upload upload-1 contains 10 bytes, expected 11", error.message)
        verify(exactly = 0) { s3.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) }
    }

    @Test
    fun `copies a staged object into an existing multipart upload`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.uploadPartCopy(any<UploadPartCopyRequest>()) } returns
            UploadPartCopyResponse.builder().build()
        val service = service(s3)
        val source = object : ObjectPath { override fun toString() = "staged-key" }
        val destination = object : ObjectPath { override fun toString() = "multipart-key" }

        val copied = service.copyToMultipartPart(source, destination, "upload-1", 2, 17)

        assertEquals(17L, copied)
        verify(exactly = 1) {
            s3.uploadPartCopy(match<UploadPartCopyRequest> {
                it.sourceBucket() == "bucket" && it.sourceKey() == "staged-key" &&
                    it.destinationBucket() == "bucket" && it.destinationKey() == "multipart-key" &&
                    it.uploadId() == "upload-1" && it.partNumber() == 2
            })
        }
    }

    @Test
    fun `explicit multipart completion rejects missing parts`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.listParts(any<ListPartsRequest>()) } returns ListPartsResponse.builder()
            .parts(Part.builder().partNumber(1).eTag("etag-1").size(5L).build())
            .isTruncated(false)
            .build()
        val service = service(s3)
        val path = object : ObjectPath { override fun toString() = "multipart-key" }

        assertFailsWith<IllegalArgumentException> {
            service.completeMultipartUpload(path, "upload-1", 2, 8)
        }

        verify(exactly = 0) { s3.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) }
    }

    @Test
    fun `explicit multipart abort delegates to S3`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.abortMultipartUpload(any<AbortMultipartUploadRequest>()) } returns
            AbortMultipartUploadResponse.builder().build()
        val service = service(s3)
        val path = object : ObjectPath { override fun toString() = "multipart-key" }

        service.abortMultipartUpload(path, "upload-1", 3)

        verify(exactly = 1) {
            s3.abortMultipartUpload(match<AbortMultipartUploadRequest> {
                it.key() == "multipart-key" && it.uploadId() == "upload-1"
            })
        }
    }

    @Test
    fun `explicit multipart abort is idempotent when the upload is already gone`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.abortMultipartUpload(any<AbortMultipartUploadRequest>()) } throws
            S3Exception.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchUpload").build())
                .statusCode(404)
                .build()
        val service = service(s3)
        val path = object : ObjectPath { override fun toString() = "multipart-key" }

        service.abortMultipartUpload(path, "upload-1", 3)

        verify(exactly = 1) { s3.abortMultipartUpload(any<AbortMultipartUploadRequest>()) }
    }

    @Test
    fun `reads of a missing key throw ObjectNotFoundException`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.getObject(any<GetObjectRequest>()) } throws NoSuchKeyException.builder().message("missing").build()
        val service = service(s3)
        val path = object : ObjectPath { override fun toString() = "missing-key" }

        assertEquals(path, assertFailsWith<ObjectNotFoundException> { service.getInputStream(path) }.path)
        assertFailsWith<ObjectNotFoundException> { service.getInputStreamRange(path, 0L..9L) }
    }

    @Test
    fun `other read failures propagate unchanged`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.getObject(any<GetObjectRequest>()) } throws
            S3Exception.builder().statusCode(503).message("slow down").build()
        val service = service(s3)
        val path = object : ObjectPath { override fun toString() = "key" }

        assertFailsWith<S3Exception> { service.getInputStream(path) }
    }

    @Test
    fun `discovers and aborts multipart uploads at one exact object path`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.listMultipartUploads(any<ListMultipartUploadsRequest>()) } returns
            ListMultipartUploadsResponse.builder()
                .uploads(
                    MultipartUpload.builder().key("staged-key").uploadId("upload-1").build(),
                    MultipartUpload.builder().key("staged-key-sibling").uploadId("upload-2").build(),
                )
                .isTruncated(false)
                .build()
        every { s3.abortMultipartUpload(any<AbortMultipartUploadRequest>()) } returns
            AbortMultipartUploadResponse.builder().build()
        val service = service(s3)
        val path = object : ObjectPath { override fun toString() = "staged-key" }

        val aborted = service.abortMultipartUploadsAtPath(path)

        assertEquals(1, aborted)
        verify(exactly = 1) {
            s3.listMultipartUploads(match<ListMultipartUploadsRequest> {
                it.prefix() == "staged-key"
            })
        }
        verify(exactly = 1) {
            s3.abortMultipartUpload(match<AbortMultipartUploadRequest> {
                it.key() == "staged-key" && it.uploadId() == "upload-1"
            })
        }
        verify(exactly = 0) {
            s3.abortMultipartUpload(match<AbortMultipartUploadRequest> {
                it.uploadId() == "upload-2"
            })
        }
    }

    @Test
    fun `uploads unknown-length input using multipart and returns total bytes`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.createMultipartUpload(any<CreateMultipartUploadRequest>()) } returns CreateMultipartUploadResponse.builder().uploadId("u1").build()
        every { s3.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) } answers {
            val req = it.invocation.args[0] as UploadPartRequest
            UploadPartResponse.builder().eTag("etag-${'$'}{req.partNumber()}").build()
        }
        every { s3.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) } returns CompleteMultipartUploadResponse.builder().build()

        val service = S3ObjectStorageService(
            bucket = "bucket",
            client = s3,
            urlPrefix = "",
            urlUploadPrefix = "",
            urlSigner = ThrowingProvider(UrlSigner::class),
            securityService = ThrowingProvider(SecurityService::class)
        )

        // Create ~6.5 MiB to force multiple parts (5 MiB + remainder)
        val size = 6_500_000
        val data = ByteArray(size) { 1 }
        val input = ByteArrayInputStream(data)

        val uploaded = service.setInputStream(object : ObjectPath { override fun toString() = "key" }, input, null)

        assertEquals(expected = size.toLong(), actual = uploaded, message = "Uploaded byte count should match input size")
        verify(exactly = 1) { s3.createMultipartUpload(any<CreateMultipartUploadRequest>()) }
        // Expect at least 2 parts for this size
        verify(atLeast = 2) { s3.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) }
        verify(exactly = 1) { s3.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) }
    }

    @Test
    fun `uploads known-length input using multipart upload`() = runTest {
        val s3 = mockk<S3Client>()
        every { s3.createMultipartUpload(any<CreateMultipartUploadRequest>()) } returns CreateMultipartUploadResponse.builder().uploadId("u1").build()
        every { s3.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) } answers {
            val req = it.invocation.args[0] as UploadPartRequest
            UploadPartResponse.builder().eTag("etag-${req.partNumber()}").build()
        }
        every { s3.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) } returns CompleteMultipartUploadResponse.builder().build()

        val service = S3ObjectStorageService(
            bucket = "bucket",
            client = s3,
            urlPrefix = "",
            urlUploadPrefix = "",
            urlSigner = ThrowingProvider(UrlSigner::class),
            securityService = ThrowingProvider(SecurityService::class)
        )

        val size = 1024
        val data = ByteArray(size) { 2 }
        val input = ByteArrayInputStream(data)

        val uploaded = service.setInputStream(object : ObjectPath { override fun toString() = "key" }, input, size.toLong())
        assertEquals(size.toLong(), uploaded)
        verify(exactly = 1) { s3.createMultipartUpload(any<CreateMultipartUploadRequest>()) }
        verify(atLeast = 1) { s3.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) }
        verify(exactly = 1) { s3.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) }
    }

    private fun service(s3: S3Client) = S3ObjectStorageService(
        bucket = "bucket",
        client = s3,
        urlPrefix = "",
        urlUploadPrefix = "",
        urlSigner = ThrowingProvider(UrlSigner::class),
        securityService = ThrowingProvider(SecurityService::class),
    )
}
