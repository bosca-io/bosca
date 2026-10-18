package bosca.storage.service

import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Provider metadata observations without asset streams, writes or revision-pinning claims. */
class ObjectStorageInfoReadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val path = StringObjectPath("asset")

    @Test
    fun `filesystem metadata observes a large sparse file without reading its payload`() = runTest {
        val file = temporaryFolder.newFile("asset")
        val length = 5L * 1024 * 1024 * 1024 + 17
        RandomAccessFile(file, "rw").use { it.setLength(length) }
        Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.parse("2026-10-17T01:02:03.123456789Z")))
        val expectedTime = Files.getLastModifiedTime(file.toPath()).toInstant().atOffset(ZoneOffset.UTC)
        val info = filesystem().getObjectInfo(path)
        assertEquals(length, info.length)
        assertEquals(expectedTime, info.modified)
        assertNull(info.etag)
        assertNull(info.version)
    }

    @Test
    fun `filesystem observations reflect replacements without inventing stable version identities`() = runTest {
        val file = temporaryFolder.newFile("asset")
        file.writeText("first")
        Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.parse("2026-10-17T01:00:00Z")))
        val storage = filesystem()
        val before = storage.getObjectInfo(path)
        file.writeText("replacement")
        Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.parse("2026-10-17T02:00:00Z")))
        val after = storage.getObjectInfo(path)
        assertEquals(5, before.length)
        assertEquals(11, after.length)
        assertEquals(OffsetDateTime.parse("2026-10-17T02:00:00Z"), after.modified)
        assertNull(after.version)
        assertNull(after.etag)
    }

    @Test
    fun `missing filesystem objects and directories are not reported as asset metadata`() = runTest {
        val storage = filesystem()
        assertEquals(path, assertFailsWith<ObjectNotFoundException> { storage.getObjectInfo(path) }.path)
        temporaryFolder.newFolder("asset")
        assertEquals(path, assertFailsWith<ObjectNotFoundException> { storage.getObjectInfo(path) }.path)
    }

    @Test
    fun `filesystem metadata follows the same symlink and path rules as object reads`() = runTest {
        val target = temporaryFolder.newFile("target").apply { writeText("data") }
        Files.createSymbolicLink(temporaryFolder.root.toPath().resolve("asset"), target.toPath())
        val storage = filesystem()
        assertEquals(4, storage.getObjectInfo(path).length)
        assertEquals("data", storage.getString(path))
        assertFailsWith<IllegalStateException> { storage.getObjectInfo(StringObjectPath("../outside")) }
        target.delete()
        assertFailsWith<ObjectNotFoundException> { storage.getObjectInfo(path) }
    }

    @Test
    fun `S3 uses HeadObject and preserves large sizes opaque tags versions and timestamps`() = runTest {
        val client = mockk<S3Client>()
        val timestamp = Instant.parse("2026-10-17T01:02:03.123456789Z")
        val caller = Thread.currentThread()
        var invoked: Thread? = null
        every { client.headObject(any<HeadObjectRequest>()) } answers {
            invoked = Thread.currentThread()
            HeadObjectResponse.builder().contentLength(Long.MAX_VALUE).eTag("\"multipart-3\"")
                .versionId("opaque/+version").lastModified(timestamp).build()
        }
        assertEquals(ObjectStorageInfo(Long.MAX_VALUE, "\"multipart-3\"", "opaque/+version",
            timestamp.atOffset(ZoneOffset.UTC)), s3(client).getObjectInfo(path))
        assertNotSame(caller, assertNotNull(invoked))
        verify(exactly = 1) { client.headObject(match<HeadObjectRequest> { it.bucket() == "bucket" && it.key() == "asset" }) }
        verify(exactly = 0) { client.getObject(any<GetObjectRequest>()) }
    }

    @Test
    fun `S3 null version sentinel and absent identifiers remain absent`() = runTest {
        val client = mockk<S3Client>()
        every { client.headObject(any<HeadObjectRequest>()) } returns
            HeadObjectResponse.builder().contentLength(0L).versionId("null").build()
        assertEquals(ObjectStorageInfo(0), s3(client).getObjectInfo(path))
    }

    @Test
    fun `S3 maps genuine absence while preserving the provider failure as cause`() = runTest {
        val client = mockk<S3Client>()
        for (failure in listOf(
            NoSuchKeyException.builder().message("missing").statusCode(404).build(),
            S3Exception.builder().message("missing").statusCode(404).build(),
        )) {
            every { client.headObject(any<HeadObjectRequest>()) } throws failure
            val missing = assertFailsWith<ObjectNotFoundException> { s3(client).getObjectInfo(path) }
            assertEquals(path, missing.path)
            assertSame(failure, missing.cause)
        }
    }

    @Test
    fun `S3 access backend and cancellation failures propagate unchanged`() = runTest {
        val client = mockk<S3Client>()
        for (status in listOf(403, 429, 503)) {
            val failure = S3Exception.builder().statusCode(status).message("unavailable").build()
            every { client.headObject(any<HeadObjectRequest>()) } throws failure
            assertSame(failure, assertFailsWith<S3Exception> { s3(client).getObjectInfo(path) })
        }
        val cancelled = CancellationException("metadata cancelled")
        every { client.headObject(any<HeadObjectRequest>()) } throws cancelled
        val observed = assertFailsWith<CancellationException> { s3(client).getObjectInfo(path) }
        assertEquals(cancelled.message, observed.message)
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === cancelled })
    }

    @Test
    fun `S3 missing bucket errors remain namespace failures`() = runTest {
        val client = mockk<S3Client>()
        for (failure in listOf(
            NoSuchBucketException.builder().statusCode(404).message("No such bucket").build(),
            S3Exception.builder().statusCode(404)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build()).build(),
        )) {
            every { client.headObject(any<HeadObjectRequest>()) } throws failure
            assertSame(failure, assertFailsWith<S3Exception> { s3(client).getObjectInfo(path) })
        }
    }

    @Test
    fun `S3 missing and negative lengths fail instead of becoming empty objects`() = runTest {
        val client = mockk<S3Client>()
        every { client.headObject(any<HeadObjectRequest>()) } returns HeadObjectResponse.builder().build()
        assertFailsWith<IllegalStateException> { s3(client).getObjectInfo(path) }
        every { client.headObject(any<HeadObjectRequest>()) } returns HeadObjectResponse.builder().contentLength(-1L).build()
        assertFailsWith<IllegalArgumentException> { s3(client).getObjectInfo(path) }
    }

    @Test
    fun `Google reads metadata with generation identity without opening a reader`() = runTest {
        val client = mockk<Storage>()
        val timestamp = OffsetDateTime.parse("2026-10-17T01:02:03.123456789+05:30")
        val blob = blob(Long.MAX_VALUE, "opaque-tag", 42L, timestamp)
        val caller = Thread.currentThread()
        var invoked: Thread? = null
        every { client.get(BlobId.of("bucket", "asset")) } answers {
            invoked = Thread.currentThread()
            blob
        }
        assertEquals(ObjectStorageInfo(Long.MAX_VALUE, "opaque-tag", "42", timestamp.withOffsetSameInstant(ZoneOffset.UTC)),
            google(client).getObjectInfo(path))
        assertNotSame(caller, assertNotNull(invoked))
        verify(exactly = 1) { client.get(BlobId.of("bucket", "asset")) }
        verify(exactly = 0) { client.reader(any<BlobId>(), *anyVararg()) }
    }

    @Test
    fun `Google optional metadata remains absent`() = runTest {
        val client = mockk<Storage>()
        every { client.get(any<BlobId>()) } returns blob(0, null, null, null)
        assertEquals(ObjectStorageInfo(0), google(client).getObjectInfo(path))
    }

    @Test
    fun `Google maps missing lookups and explicit not found errors`() = runTest {
        val client = mockk<Storage>()
        every { client.get(any<BlobId>()) } returns null
        assertEquals(path, assertFailsWith<ObjectNotFoundException> { google(client).getObjectInfo(path) }.path)
        val failure = StorageException(404, "missing")
        every { client.get(any<BlobId>()) } throws failure
        val missing = assertFailsWith<ObjectNotFoundException> { google(client).getObjectInfo(path) }
        assertEquals(path, missing.path)
        assertSame(failure, missing.cause)
    }

    @Test
    fun `Google access backend and cancellation failures propagate unchanged`() = runTest {
        val client = mockk<Storage>()
        for (status in listOf(403, 429, 503)) {
            val failure = StorageException(status, "unavailable")
            every { client.get(any<BlobId>()) } throws failure
            assertSame(failure, assertFailsWith<StorageException> { google(client).getObjectInfo(path) })
        }
        val cancelled = CancellationException("metadata cancelled")
        every { client.get(any<BlobId>()) } throws cancelled
        val observed = assertFailsWith<CancellationException> { google(client).getObjectInfo(path) }
        assertEquals(cancelled.message, observed.message)
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === cancelled })
    }

    @Test
    fun `no-op storage reports absent metadata without creating objects`() = runTest {
        assertEquals(path, assertFailsWith<ObjectNotFoundException> { NoOpObjectStorageService().getObjectInfo(path) }.path)
    }

    private fun filesystem() = FileSystemObjectStorageService("", "", mockk(), temporaryFolder.root.path, mockk())

    private fun s3(client: S3Client) = S3ObjectStorageService("bucket", client, "", "", mockk(), mockk())

    private fun google(client: Storage) = GoogleObjectStorageService("bucket", client, "", "", mockk(), mockk())

    private fun blob(length: Long, tag: String?, version: Long?, modified: OffsetDateTime?): Blob = mockk {
        every { size } returns length
        every { etag } returns tag
        every { generation } returns version
        every { updateTimeOffsetDateTime } returns modified
    }
}
