package bosca.storage.service

import com.google.cloud.ReadChannel
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoogleObjectStorageServiceTest {

    private val storage = mockk<Storage>()
    private val path = StringObjectPath("packs/pack-1.pack")

    private fun service() = GoogleObjectStorageService(
        bucket = "bucket",
        storage = storage,
        urlPrefix = "http://localhost",
        urlUploadPrefix = "http://localhost",
        urlSigner = mockk(),
        securityService = mockk(),
    )

    /** A blob whose lazy reader fails its first read with [failure]. */
    private fun blobFailingWith(failure: IOException) {
        val reader = mockk<ReadChannel>(relaxed = true)
        every { reader.isOpen } returns true
        every { reader.read(any<ByteBuffer>()) } throws failure
        every { reader.limit(any()) } returns reader
        every { storage.get(any<BlobId>()) } returns mockk<Blob>()
        every { storage.reader(any<BlobId>(), *anyVararg()) } returns reader
    }

    @Test
    fun `a range read ends at the range's exclusive end offset and reads the limited channel`() = runTest {
        val reader = mockk<ReadChannel>(relaxed = true)
        val limited = mockk<ReadChannel>(relaxed = true)
        every { reader.limit(any()) } returns limited
        every { limited.isOpen } returns true
        every { limited.read(any<ByteBuffer>()) } answers {
            firstArg<ByteBuffer>().put(7)
            1
        }
        every { storage.get(any<BlobId>()) } returns mockk<Blob>()
        every { storage.reader(any<BlobId>(), *anyVararg()) } returns reader

        service().getInputStreamRange(path, 100L..199L).use { stream ->
            assertEquals(7, stream.read())
        }

        // GCS's limit() is an end offset, [seek, limit), not a length: bytes 100..199 end at 200.
        verify { reader.seek(100) }
        verify { reader.limit(200) }
        verify { limited.read(any<ByteBuffer>()) }
    }

    @Test
    fun `an open-ended range read sets no end offset`() = runTest {
        val reader = mockk<ReadChannel>(relaxed = true)
        every { reader.isOpen } returns true
        every { reader.read(any<ByteBuffer>()) } answers {
            firstArg<ByteBuffer>().put(7)
            1
        }
        every { storage.get(any<BlobId>()) } returns mockk<Blob>()
        every { storage.reader(any<BlobId>(), *anyVararg()) } returns reader

        service().getInputStreamRange(path, 100L..Long.MAX_VALUE).use { stream ->
            assertEquals(7, stream.read())
        }

        verify { reader.seek(100) }
        verify(exactly = 0) { reader.limit(any()) }
    }

    @Test
    fun `a missing blob is reported as ObjectNotFoundException`() = runTest {
        every { storage.get(any<BlobId>()) } returns null

        assertEquals(path, assertFailsWith<ObjectNotFoundException> { service().getInputStream(path) }.path)
        assertFailsWith<ObjectNotFoundException> { service().getInputStreamRange(path, 0L..9L) }
    }

    @Test
    fun `a blob deleted between lookup and first read is reported as ObjectNotFoundException`() = runTest {
        blobFailingWith(IOException(StorageException(404, "No such object")))

        service().getInputStream(path).use { stream ->
            assertEquals(path, assertFailsWith<ObjectNotFoundException> { stream.read(ByteArray(16)) }.path)
        }
        service().getInputStreamRange(path, 0L..9L).use { stream ->
            assertFailsWith<ObjectNotFoundException> { stream.read() }
        }
    }

    @Test
    fun `other read failures propagate unchanged`() = runTest {
        blobFailingWith(IOException(StorageException(503, "Backend unavailable")))

        service().getInputStream(path).use { stream ->
            assertFailsWith<IOException> { stream.read(ByteArray(16)) }
        }
    }
}
