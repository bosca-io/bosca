package bosca.storage.service

import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileSystemObjectStorageServiceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun storage() = FileSystemObjectStorageService(
        urlPrefix = "http://localhost",
        urlUploadPrefix = "http://localhost",
        urlSigner = mockk(),
        basePath = temporaryFolder.root.absolutePath,
        securityService = mockk(),
    )

    @Test
    fun `range buffer reads do not fall back to single byte reads`() = runTest {
        val data = ByteArray(4096) { it.toByte() }
        temporaryFolder.newFile("pack").writeBytes(data)

        spyk(storage().getInputStreamRange(StringObjectPath("pack"), 100L..2099L)).use { stream ->
            val buffer = ByteArray(2100) { -1 }
            assertEquals(2000, stream.read(buffer, 50, 2050))
            assertContentEquals(data.copyOfRange(100, 2100), buffer.copyOfRange(50, 2050))
            assertTrue(buffer.take(50).all { it == (-1).toByte() })
            assertTrue(buffer.drop(2050).all { it == (-1).toByte() })
            assertEquals(0, stream.available())
            assertEquals(-1, stream.read(buffer, 0, buffer.size))
            verify(exactly = 0) { stream.read() }
        }
    }

    @Test
    fun `single byte and buffer reads share the inclusive range boundary`() = runTest {
        temporaryFolder.newFile("pack").writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5))

        storage().getInputStreamRange(StringObjectPath("pack"), 1L..4L).use { stream ->
            assertEquals(4, stream.available())
            assertEquals(1, stream.read())
            val buffer = ByteArray(2)
            assertEquals(2, stream.read(buffer))
            assertContentEquals(byteArrayOf(2, 3), buffer)
            assertEquals(1, stream.available())
            assertEquals(1, stream.read(buffer, 1, 1))
            assertContentEquals(byteArrayOf(2, 4), buffer)
            assertEquals(-1, stream.read())
            assertEquals(-1, stream.read(buffer))
        }
    }

    @Test
    fun `range reads stop at the file end and allow empty reads at EOF`() = runTest {
        temporaryFolder.newFile("pack").writeBytes(byteArrayOf(0, 1, 2))

        for (range in listOf(1L..100L, 3L..100L, 2L..1L)) {
            storage().getInputStreamRange(StringObjectPath("pack"), range).use { stream ->
                val expected = if (range.first == 1L) byteArrayOf(1, 2) else byteArrayOf()
                assertContentEquals(expected, stream.readBytes())
                assertEquals(0, stream.available())
                assertEquals(0, stream.read(ByteArray(0)))
                assertEquals(0, stream.read(ByteArray(2), 2, 0))
                assertEquals(-1, stream.read())
            }
        }
    }

    @Test
    fun `range buffer reads validate offsets and lengths before reading`() = runTest {
        temporaryFolder.newFile("pack").writeBytes(byteArrayOf(0, 1, 2))

        storage().getInputStreamRange(StringObjectPath("pack"), 0L..2L).use { stream ->
            val buffer = ByteArray(2)
            for ((offset, length) in listOf(-1 to 1, 0 to -1, 1 to 2, 3 to 0, Int.MAX_VALUE to 1)) {
                assertFailsWith<IndexOutOfBoundsException> { stream.read(buffer, offset, length) }
            }
            assertEquals(3, stream.available())
            assertContentEquals(byteArrayOf(0, 1, 2), stream.readBytes())
            assertFailsWith<IndexOutOfBoundsException> { stream.read(buffer, -1, 0) }
        }
    }

    @Test
    fun `range reads handle a file truncated after opening`() = runTest {
        val file = temporaryFolder.newFile("pack")
        val service = storage()

        for (singleByte in listOf(false, true)) {
            file.writeBytes(byteArrayOf(0, 1, 2))
            service.getInputStreamRange(StringObjectPath("pack"), 0L..2L).use { stream ->
                RandomAccessFile(file, "rw").use { it.setLength(0) }
                assertEquals(-1, if (singleByte) stream.read() else stream.read(ByteArray(2), 0, 2))
                assertEquals(0, stream.available())
                assertEquals(-1, stream.read())
                assertEquals(-1, stream.read(ByteArray(2)))
            }
        }
    }

    @Test
    fun `reads of a missing object throw ObjectNotFoundException`() = runTest {
        val storage = storage()
        val missing = StringObjectPath("missing/log.ndjson")

        assertEquals(missing, assertFailsWith<ObjectNotFoundException> { storage.getInputStream(missing) }.path)
        assertFailsWith<ObjectNotFoundException> { storage.getString(missing) }
        assertFailsWith<ObjectNotFoundException> { storage.getInputStreamRange(missing, 0L..9L) }
    }

    @Test
    fun `an unreadable object is a storage failure, not a missing one`() = runTest {
        temporaryFolder.newFolder("directory")
        // The type check proves it: ObjectNotFoundException is not a FileNotFoundException.
        assertFailsWith<java.io.FileNotFoundException> {
            storage().getInputStream(StringObjectPath("directory"))
        }
    }

    @Test
    fun `toFile converts ObjectPath to File`() {
        val path = StringObjectPath("test/file.txt")
        val file = path.toFile("/tmp")
        assertNotNull(file)
        assertTrue(file.path.endsWith("test/file.txt"))
    }

    @Test
    fun `toFile rejects path traversal`() {
        val path = StringObjectPath("../etc/passwd")
        assertFailsWith<IllegalStateException> {
            path.toFile("/tmp")
        }
    }
}
