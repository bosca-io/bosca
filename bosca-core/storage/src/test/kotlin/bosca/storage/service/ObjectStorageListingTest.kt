package bosca.storage.service

import com.google.api.gax.paging.Page
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3Object
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Enumerates metadata without asset reads or claiming an immutable source capture. */
class ObjectStorageListingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `filesystem pages nested regular files by literal prefix and sorted key`() = runTest {
        write("a/file", "one")
        write("a.txt", "two")
        write("a0", "three")
        write("excluded/file", "four")
        val storage = filesystem()
        val all = storage.listObjects("a", 0, 20)
        assertEquals(listOf("a.txt", "a/file", "a0"), all.map { it.key })
        for (entry in all) assertEquals(storage.getObjectInfo(StringObjectPath(entry.key)), entry.info)
        assertEquals(all.take(1), storage.listObjects("a", 0, 1))
        assertEquals(all.drop(1).take(1), storage.listObjects("a", 1, 1))
        assertEquals(listOf("a/file"), storage.listObjects("a/", 0, 20).map { it.key })
        assertTrue(storage.listObjects("a", 3, 1).isEmpty())
        assertTrue(storage.listObjects("a", Long.MAX_VALUE, Int.MAX_VALUE).isEmpty())
    }

    @Test
    fun `filesystem lists large sparse assets without opening bodies and observes later changes`() = runTest {
        val key = "assets/🖼 +%/file"
        write(key, "data")
        val file = temporaryFolder.root.toPath().resolve(key).toFile()
        RandomAccessFile(file, "rw").use { it.setLength(5L * 1024 * 1024 * 1024 + 17) }
        val storage = filesystem()
        assertEquals(5L * 1024 * 1024 * 1024 + 17, storage.listObjects("assets/🖼 +%/", 0, 1).single().info.length)
        assertTrue(storage.listObjects("assets/🖼  %/", 0, 1).isEmpty())
        file.writeText("changed")
        assertEquals(7, storage.listObjects("assets/", 0, 1).single().info.length)
        assertTrue(file.delete())
        assertTrue(storage.listObjects("assets/", 0, 1).isEmpty())
    }

    @Test
    fun `filesystem follows selected aliases while unrelated cycles and broken links are outside scope`() = runTest {
        write("outside/file", "data")
        val root = temporaryFolder.root.toPath()
        Files.createDirectories(root.resolve("assets"))
        Files.createDirectories(root.resolve("ignored"))
        Files.createSymbolicLink(root.resolve("assets/alias"), root.resolve("outside"))
        Files.createSymbolicLink(root.resolve("ignored/cycle"), root.resolve("ignored"))
        Files.createSymbolicLink(root.resolve("ignored/missing"), root.resolve("missing"))
        assertEquals(listOf("assets/alias/file"), filesystem().listObjects("assets/", 0, 10).map { it.key })
        assertFailsWith<IOException> { filesystem().listObjects("ignored/", 0, 10) }
    }

    @Test
    fun `selected dangling links and missing roots fail instead of producing complete empty inventories`() = runTest {
        val root = temporaryFolder.root.toPath()
        Files.createSymbolicLink(root.resolve("broken"), root.resolve("missing"))
        assertFailsWith<IOException> { filesystem().listObjects("broken", 0, 10) }
        assertFailsWith<IOException> { filesystem(root.resolve("missing").toString()).listObjects("", 0, 10) }
    }

    @Test
    fun `all backends reject invalid page bounds before enumeration`() = runTest {
        val s3 = mockk<S3Client>()
        val google = mockk<Storage>()
        for (storage in listOf(filesystem(), s3(s3), google(google), NoOpObjectStorageService())) {
            for ((offset, limit) in listOf(-1L to 1, 0L to 0, 0L to -1)) {
                assertFailsWith<IllegalArgumentException> { storage.listObjects("", offset, limit) }
            }
        }
        verify(exactly = 0) { s3.listObjectsV2(any<ListObjectsV2Request>()) }
        verify(exactly = 0) { google.list(any<String>(), *anyVararg()) }
    }

    @Test
    fun `S3 offsets span provider pages without object HEAD or GET calls`() = runTest {
        val client = mockk<S3Client>()
        val requests = mutableListOf<ListObjectsV2Request>()
        val time = Instant.parse("2026-10-17T01:02:03Z")
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } answers {
            val request = firstArg<ListObjectsV2Request>().also { requests.add(it) }
            when (request.continuationToken()) {
                null -> page(listOf(item("assets/", 0), item("assets/a", 1)), "next/+token")
                else -> page(listOf(item("assets/b", Long.MAX_VALUE, time), item("assets/c", 3)))
            }
        }
        val entries = s3(client).listObjects("assets/", 1, 2)
        assertEquals(listOf("assets/a", "assets/b"), entries.map { it.key })
        assertEquals(ObjectStorageInfo(Long.MAX_VALUE, "\"tag\"", modified = time.atOffset(ZoneOffset.UTC)), entries.last().info)
        assertNull(entries.last().info.version)
        assertEquals(listOf(null, "next/+token"), requests.map { it.continuationToken() })
        assertTrue(requests.all { it.bucket() == "bucket" && it.prefix() == "assets/" && it.delimiter() == null })
        assertTrue(requests.all { it.maxKeys() in 1..1000 })
        verify(exactly = 0) { client.headObject(any<HeadObjectRequest>()) }
        verify(exactly = 0) { client.getObject(any<GetObjectRequest>()) }
    }

    @Test
    fun `S3 large offsets do not overflow and empty intermediate pages are followed`() = runTest {
        val client = mockk<S3Client>()
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } returnsMany listOf(
            page(emptyList(), "next"), page(listOf(item("a", 1))),
        )
        assertTrue(s3(client).listObjects("", Long.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        verify(exactly = 2) { client.listObjectsV2(match<ListObjectsV2Request> { it.maxKeys() == 1000 }) }
    }

    @Test
    fun `S3 stops when the requested page is full and preserves literal object keys`() = runTest {
        val client = mockk<S3Client>()
        val key = "assets/🖼 +%/file"
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } returns page(listOf(item(key, 1)), "unused")
        assertEquals(listOf(key), s3(client).listObjects("assets/🖼 +%/", 0, 1).map { it.key })
        verify(exactly = 1) { client.listObjectsV2(any<ListObjectsV2Request>()) }
    }

    @Test
    fun `S3 incomplete and cyclic pagination fails rather than looping or returning partial inventory`() = runTest {
        for (pages in listOf(
            listOf(ListObjectsV2Response.builder().isTruncated(true).build()),
            listOf(page(emptyList(), "same"), page(emptyList(), "same")),
            listOf(page(emptyList(), "a"), page(emptyList(), "b"), page(emptyList(), "a")),
        )) {
            val client = mockk<S3Client>()
            every { client.listObjectsV2(any<ListObjectsV2Request>()) } returnsMany pages
            assertFailsWith<IllegalStateException> { withTimeout(3000) { s3(client).listObjects("", 0, 1) } }
        }
    }

    @Test
    fun `S3 failures after an earlier page do not turn into partial results`() = runTest {
        val client = mockk<S3Client>()
        val failure = NoSuchBucketException.builder().statusCode(404).message("missing bucket").build()
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } answers {
            if (firstArg<ListObjectsV2Request>().continuationToken() == null) page(listOf(item("a", 1)), "next")
            else throw failure
        }
        assertSame(failure, assertFailsWith<NoSuchBucketException> { s3(client).listObjects("", 0, 2) })
    }

    @Test
    fun `S3 permission errors and cancellation propagate without an empty page`() = runTest {
        val client = mockk<S3Client>()
        for (status in listOf(403, 503)) {
            val failure = S3Exception.builder().statusCode(status).build()
            every { client.listObjectsV2(any<ListObjectsV2Request>()) } throws failure
            assertSame(failure, assertFailsWith<S3Exception> { s3(client).listObjects("", 0, 1) })
        }
        val cancelled = CancellationException("listing cancelled")
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } throws cancelled
        val observed = assertFailsWith<CancellationException> { s3(client).listObjects("", 0, 1) }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === cancelled })
    }

    @Test
    fun `S3 malformed selected metadata is not accepted as a complete entry`() = runTest {
        val client = mockk<S3Client>()
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } returns page(listOf(S3Object.builder().size(1L).build()))
        assertFailsWith<IllegalStateException> { s3(client).listObjects("", 0, 1) }
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } returns page(listOf(S3Object.builder().key("a").build()))
        assertFailsWith<IllegalStateException> { s3(client).listObjects("", 0, 1) }
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } returns page(listOf(item("a", -1)))
        assertFailsWith<IllegalArgumentException> { s3(client).listObjects("", 0, 1) }
    }

    @Test
    fun `Google consumes only the requested metadata window and preserves generation identity`() = runTest {
        val client = mockk<Storage>()
        var reads = 0
        val page = mockk<Page<Blob>>()
        every { page.iterateAll() } returns Iterable {
            val entries = listOf(blob("assets/a"), blob("assets/b"), blob("assets/c"))
            object : Iterator<Blob> {
                override fun hasNext(): Boolean = reads < entries.size
                override fun next(): Blob = entries[reads++].also { check(reads <= 2) { "Read beyond requested page" } }
            }
        }
        every { client.list("bucket", *anyVararg()) } returns page
        val entries = google(client).listObjects("assets/", 1, 1)
        assertEquals(listOf("assets/b"), entries.map { it.key })
        assertEquals(ObjectStorageInfo(7, "tag", "42"), entries.single().info)
        assertEquals(2, reads)
        verify(exactly = 1) { client.list("bucket", Storage.BlobListOption.prefix("assets/"), any<Storage.BlobListOption>()) }
        verify(exactly = 0) { client.get(any<BlobId>()) }
        verify(exactly = 0) { client.reader(any<BlobId>(), *anyVararg()) }
    }

    @Test
    fun `Google large offsets finish at the available inventory without truncation`() = runTest {
        val client = mockk<Storage>()
        val page = mockk<Page<Blob>>()
        every { page.iterateAll() } returns listOf(blob("a"), blob("b"))
        every { client.list("bucket", *anyVararg()) } returns page
        assertTrue(google(client).listObjects("", Long.MAX_VALUE, Int.MAX_VALUE).isEmpty())
    }

    @Test
    fun `Google missing namespace and later-page failures propagate`() = runTest {
        val client = mockk<Storage>()
        for (status in listOf(404, 403, 503)) {
            val failure = StorageException(status, "listing failed")
            every { client.list("bucket", *anyVararg()) } throws failure
            assertSame(failure, assertFailsWith<StorageException> { google(client).listObjects("", 0, 1) })
        }
        val failure = StorageException(503, "later page failed")
        val page = mockk<Page<Blob>>()
        every { page.iterateAll() } returns Iterable {
            object : Iterator<Blob> {
                var first = true
                override fun hasNext(): Boolean = if (first) true else throw failure
                override fun next(): Blob = blob("a").also { first = false }
            }
        }
        every { client.list("bucket", *anyVararg()) } returns page
        assertSame(failure, assertFailsWith<StorageException> { google(client).listObjects("", 0, 2) })
    }

    @Test
    fun `Google cancellation during provider iteration stays cancellation`() = runTest {
        val client = mockk<Storage>()
        val cancelled = CancellationException("listing cancelled")
        val page = mockk<Page<Blob>>()
        every { page.iterateAll() } returns Iterable {
            object : Iterator<Blob> {
                override fun hasNext(): Boolean = throw cancelled
                override fun next(): Blob = error("no entry available")
            }
        }
        every { client.list("bucket", *anyVararg()) } returns page
        val observed = assertFailsWith<CancellationException> { google(client).listObjects("", 0, 1) }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === cancelled })
    }

    @Test
    fun `no-op storage exposes an empty inventory for valid pages`() = runTest {
        assertTrue(NoOpObjectStorageService().listObjects("assets/", 0, 100).isEmpty())
    }

    private fun filesystem(base: String = temporaryFolder.root.path) =
        FileSystemObjectStorageService("", "", mockk(), base, mockk())

    private fun s3(client: S3Client) = S3ObjectStorageService("bucket", client, "", "", mockk(), mockk())

    private fun google(client: Storage) = GoogleObjectStorageService("bucket", client, "", "", mockk(), mockk())

    private fun write(key: String, value: String) {
        temporaryFolder.root.resolve(key).apply {
            parentFile.mkdirs()
            writeText(value)
        }
    }

    private fun item(key: String, size: Long, time: Instant? = null): S3Object =
        S3Object.builder().key(key).size(size).eTag("\"tag\"").lastModified(time).build()

    private fun page(entries: List<S3Object>, next: String? = null): ListObjectsV2Response =
        ListObjectsV2Response.builder().contents(entries).isTruncated(next != null).nextContinuationToken(next).build()

    private fun blob(key: String): Blob = mockk {
        every { name } returns key
        every { size } returns 7L
        every { etag } returns "tag"
        every { generation } returns 42L
        every { updateTimeOffsetDateTime } returns null
    }
}
