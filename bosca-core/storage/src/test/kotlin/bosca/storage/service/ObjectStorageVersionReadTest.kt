package bosca.storage.service

import com.google.cloud.ReadChannel
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.S3Exception
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exact retained revisions must never degrade to a read of current content. */
class ObjectStorageVersionReadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val path = StringObjectPath("asset")

    @Test
    fun `S3 reads the selected opaque version without looking up the current object`() = runTest {
        val client = mockk<S3Client>()
        val version = "old/+% version"
        val caller = Thread.currentThread()
        var invoked: Thread? = null
        every { client.getObject(any<GetObjectRequest>()) } answers {
            invoked = Thread.currentThread()
            val request = firstArg<GetObjectRequest>()
            response(if (request.versionId() == version) "old" else "new", request.versionId())
        }
        assertEquals("old", s3(client).getInputStream(path, version).use { it.readAllBytes().decodeToString() })
        assertNotSame(caller, invoked)
        verify(exactly = 1) {
            client.getObject(match<GetObjectRequest> {
                it.bucket() == "bucket" && it.key() == "asset" && it.versionId() == version
            })
        }
        verify(exactly = 0) { client.headObject(any<HeadObjectRequest>()) }
    }

    @Test
    fun `S3 rejects missing substituted and null response versions and aborts their bodies`() = runTest {
        val client = mockk<S3Client>()
        for (returned in listOf(null, "", "null", "new")) {
            val aborted = AtomicBoolean()
            every { client.getObject(any<GetObjectRequest>()) } returns response("new", returned, aborted)
            assertFailsWith<IllegalStateException> { s3(client).getInputStream(path, "old") }
            assertTrue(aborted.get())
        }
        verify(exactly = 4) { client.getObject(match<GetObjectRequest> { it.versionId() == "old" }) }
        verify(exactly = 0) { client.getObject(match<GetObjectRequest> { it.versionId() == null }) }
    }

    @Test
    fun `S3 rejects blank and mutable null identifiers before backend access`() = runTest {
        val client = mockk<S3Client>()
        for (version in listOf("", " ", "\n", "null")) {
            assertFailsWith<IllegalArgumentException> { s3(client).getInputStream(path, version) }
        }
        verify(exactly = 0) { client.getObject(any<GetObjectRequest>()) }
    }

    @Test
    fun `S3 reports an unavailable retained version without falling back to current content`() = runTest {
        val client = mockk<S3Client>()
        val failure = S3Exception.builder().statusCode(404)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchVersion").build()).build()
        every { client.getObject(any<GetObjectRequest>()) } throws failure
        val missing = assertFailsWith<ObjectNotFoundException> { s3(client).getInputStream(path, "old") }
        assertEquals(path, missing.path)
        assertSame(failure, missing.cause)
        verify(exactly = 1) { client.getObject(match<GetObjectRequest> { it.versionId() == "old" }) }
        verify(exactly = 0) { client.getObject(match<GetObjectRequest> { it.versionId() == null }) }
    }

    @Test
    fun `S3 preserves namespace authorization backend and cancellation failures`() = runTest {
        val client = mockk<S3Client>()
        val failures = listOf(
            NoSuchBucketException.builder().statusCode(404).build(),
            S3Exception.builder().statusCode(404)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build()).build(),
        ) + listOf(403, 405, 412, 429, 503).map { S3Exception.builder().statusCode(it).build() }
        for (failure in failures) {
            every { client.getObject(any<GetObjectRequest>()) } throws failure
            assertSame(failure, assertFailsWith<S3Exception> { s3(client).getInputStream(path, "old") })
        }
        val cancellation = CancellationException("version read cancelled")
        every { client.getObject(any<GetObjectRequest>()) } throws cancellation
        val observed = assertFailsWith<CancellationException> { s3(client).getInputStream(path, "old") }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === cancellation })
    }

    @Test
    fun `S3 version streams abort early reads and keep fully read connections reusable`() = runTest {
        val client = mockk<S3Client>()
        val early = AtomicBoolean()
        every { client.getObject(any<GetObjectRequest>()) } returns response("old", "old", early)
        s3(client).getInputStream(path, "old").use { assertEquals('o'.code, it.read()) }
        assertTrue(early.get())
        val complete = AtomicBoolean()
        every { client.getObject(any<GetObjectRequest>()) } returns response("old", "old", complete)
        s3(client).getInputStream(path, "old").use { assertEquals("old", it.readAllBytes().decodeToString()) }
        assertFalse(complete.get())
    }

    @Test
    fun `S3 cancellation during version open aborts the unclaimed response`() = runTest {
        val client = mockk<S3Client>()
        val aborted = AtomicBoolean()
        val work = async { s3(client).getInputStream(path, "old") }
        every { client.getObject(any<GetObjectRequest>()) } answers {
            work.cancel()
            response("old", "old", aborted)
        }
        assertFailsWith<CancellationException> { work.await() }
        work.join()
        assertTrue(aborted.get())
    }

    @Test
    fun `S3 response rejection keeps its failure when abort also fails`() = runTest {
        val client = mockk<S3Client>()
        val cleanup = IOException("abort failed")
        every { client.getObject(any<GetObjectRequest>()) } returns ResponseInputStream(
            GetObjectResponse.builder().versionId("new").build(),
            AbortableInputStream.create(ByteArrayInputStream("new".toByteArray())) { throw cleanup },
        )
        val failure = assertFailsWith<IllegalStateException> { s3(client).getInputStream(path, "old") }
        assertEquals("Object response does not match the requested version", failure.message)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { cause ->
            cause.suppressed.any { it === cleanup }
        })
    }

    @Test
    fun `Google reads the selected generation directly without checking the current object`() = runTest {
        val client = mockk<Storage>()
        val reader = reader("old")
        val caller = Thread.currentThread()
        var invoked: Thread? = null
        every { client.reader(BlobId.of("bucket", "asset", Long.MAX_VALUE)) } answers {
            invoked = Thread.currentThread()
            reader
        }
        assertEquals("old", google(client).getInputStream(path, Long.MAX_VALUE.toString()).use {
            it.readAllBytes().decodeToString()
        })
        assertNotSame(caller, invoked)
        verify(exactly = 1) { reader.close() }
        verify(exactly = 0) { client.get(any<BlobId>()) }
        verify(exactly = 0) { client.reader(BlobId.of("bucket", "asset")) }
    }

    @Test
    fun `Google rejects noncanonical nonpositive and overflowing generations before backend access`() = runTest {
        val client = mockk<Storage>()
        for (version in listOf("", " ", "null", "old", "0", "-1", "+1", "01", "1 ", "9223372036854775808")) {
            assertFailsWith<IllegalArgumentException> { google(client).getInputStream(path, version) }
        }
        verify(exactly = 0) { client.reader(any<BlobId>(), *anyVararg()) }
        verify(exactly = 0) { client.get(any<BlobId>()) }
    }

    @Test
    fun `Google maps missing generation failures at open without a current-object fallback`() = runTest {
        val client = mockk<Storage>()
        val failure = StorageException(404, "No such generation")
        every { client.reader(BlobId.of("bucket", "asset", 42)) } throws failure
        val missing = assertFailsWith<ObjectNotFoundException> { google(client).getInputStream(path, "42") }
        assertEquals(path, missing.path)
        assertSame(failure, missing.cause)
        verify(exactly = 1) { client.reader(BlobId.of("bucket", "asset", 42)) }
        verify(exactly = 0) { client.get(any<BlobId>()) }
        verify(exactly = 0) { client.reader(BlobId.of("bucket", "asset")) }
    }

    @Test
    fun `Google lazy missing generation failures close the channel on read and skip`() = runTest {
        val client = mockk<Storage>()
        val reader = reader("old")
        val failure = IOException(StorageException(404, "Generation deleted"))
        every { reader.read(any<ByteBuffer>()) } throws failure
        every { client.reader(BlobId.of("bucket", "asset", 42)) } returns reader
        repeat(3) { operation ->
            google(client).getInputStream(path, "42").use { stream ->
                val missing = assertFailsWith<ObjectNotFoundException> {
                    when (operation) {
                        0 -> stream.read()
                        1 -> stream.read(ByteArray(16))
                        else -> stream.skip(1)
                    }
                }
                assertSame(failure, missing.cause)
            }
        }
        verify(exactly = 3) { reader.close() }
    }

    @Test
    fun `Google preserves provider and cancellation failures when opening retained generations`() = runTest {
        val client = mockk<Storage>()
        for (status in listOf(403, 412, 429, 503)) {
            val failure = StorageException(status, "Unavailable generation")
            every { client.reader(BlobId.of("bucket", "asset", 42)) } throws failure
            assertSame(failure, assertFailsWith<StorageException> { google(client).getInputStream(path, "42") })
        }
        val cancellation = CancellationException("generation read cancelled")
        every { client.reader(BlobId.of("bucket", "asset", 42)) } throws cancellation
        val observed = assertFailsWith<CancellationException> { google(client).getInputStream(path, "42") }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === cancellation })
    }

    @Test
    fun `Google cancellation during generation open closes the unclaimed reader`() = runTest {
        val client = mockk<Storage>()
        val reader = reader("old")
        val work = async { google(client).getInputStream(path, "42") }
        every { client.reader(BlobId.of("bucket", "asset", 42)) } answers {
            work.cancel()
            reader
        }
        assertFailsWith<CancellationException> { work.await() }
        work.join()
        verify(exactly = 1) { reader.close() }
    }

    @Test
    fun `Google lazy access and cancellation failures propagate and close the selected reader`() = runTest {
        val client = mockk<Storage>()
        val reader = reader("old")
        every { client.reader(BlobId.of("bucket", "asset", 42)) } returns reader
        val failure = IOException(StorageException(503, "Unavailable generation"))
        every { reader.read(any<ByteBuffer>()) } throws failure
        google(client).getInputStream(path, "42").use {
            assertSame(failure, assertFailsWith<IOException> { it.read() })
        }
        val cancellation = CancellationException("generation stream cancelled")
        every { reader.read(any<ByteBuffer>()) } throws cancellation
        google(client).getInputStream(path, "42").use {
            assertSame(cancellation, assertFailsWith<CancellationException> { it.read() })
        }
        verify(exactly = 2) { reader.close() }
    }

    @Test
    fun `filesystem cannot claim to read an old revision of a current file`() = runTest {
        temporaryFolder.newFile("asset").writeText("current")
        val storage = FileSystemObjectStorageService("", "", mockk(), temporaryFolder.root.path, mockk())
        assertFailsWith<UnsupportedOperationException> { storage.getInputStream(path, "old") }
        assertEquals("current", storage.getString(path))
    }

    @Test
    fun `no-op storage reports an absent retained version`() = runTest {
        val storage = NoOpObjectStorageService()
        assertEquals(path, assertFailsWith<ObjectNotFoundException> { storage.getInputStream(path, "old") }.path)
        assertFailsWith<IllegalArgumentException> { storage.getInputStream(path, " ") }
    }

    private fun s3(client: S3Client) = S3ObjectStorageService("bucket", client, "", "", mockk(), mockk())

    private fun google(client: Storage) = GoogleObjectStorageService("bucket", client, "", "", mockk(), mockk())

    private fun response(content: String, version: String?, aborted: AtomicBoolean = AtomicBoolean()) = ResponseInputStream(
        GetObjectResponse.builder().contentLength(content.length.toLong()).versionId(version).build(),
        AbortableInputStream.create(ByteArrayInputStream(content.toByteArray())) { aborted.set(true) },
    )

    private fun reader(content: String): ReadChannel {
        val source = ByteArrayInputStream(content.toByteArray())
        return mockk<ReadChannel>(relaxed = true) {
            every { isOpen } returns true
            every { read(any<ByteBuffer>()) } answers {
                val target = firstArg<ByteBuffer>()
                val buffer = ByteArray(target.remaining())
                val count = source.read(buffer)
                if (count > 0) target.put(buffer, 0, count)
                count
            }
        }
    }
}
