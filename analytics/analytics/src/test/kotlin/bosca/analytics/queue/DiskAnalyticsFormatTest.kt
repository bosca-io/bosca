package bosca.analytics.queue

import java.io.RandomAccessFile
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.*

class DiskAnalyticsFormatTest {
    private val directory = Files.createTempDirectory("analytics-format")
    @AfterTest fun cleanup() { check(directory.toFile().deleteRecursively()) }

    @Test fun `unsupported headers and damaged footer counts are rejected without mutation`() {
        val path = directory.resolve("data")
        RandomAccessFile(path.toFile(), "rw").use { file ->
            DiskAnalyticsFormat.header(file, 1, 10)
            DiskAnalyticsFormat.footer(file, 2, 1)
            for (field in listOf(0L, 8L)) {
                val original = Files.readAllBytes(path)
                file.seek(field); file.writeInt(0)
                val damaged = Files.readAllBytes(path)
                assertFailsWith<CorruptAnalyticsSegmentException> { DiskAnalyticsFormat.readHeader(file) }
                assertContentEquals(damaged, Files.readAllBytes(path)); Files.write(path, original)
            }
            file.seek(DiskAnalyticsFormat.HEADER_BYTES + 4); file.writeInt(0)
            assertFailsWith<CorruptAnalyticsSegmentException> { DiskAnalyticsFormat.readFooter(file) }
            for ((events, records) in listOf(-1L to 1L, 1L to -1L)) {
                file.seek(DiskAnalyticsFormat.HEADER_BYTES); DiskAnalyticsFormat.footer(file, events, records)
                assertFailsWith<CorruptAnalyticsSegmentException> { DiskAnalyticsFormat.readFooter(file) }
            }
            file.setLength(0)
            assertFailsWith<CorruptAnalyticsSegmentException> { DiskAnalyticsFormat.readFooter(file) }
        }
    }

    @Test fun `valid checksums cannot bypass frame size limits`() {
        val path = directory.resolve("state")
        for (length in listOf(0, -1)) {
            val header = ByteBuffer.allocate(8).putInt(length).putInt(0).array()
            Files.write(path, ByteBuffer.allocate(12).put(header).putInt(DiskAnalyticsFormat.checksum(header)).array())
            RandomAccessFile(path.toFile(), "r").use { file ->
                assertFailsWith<CorruptAnalyticsSegmentException> { DiskAnalyticsFormat.readFrame(file, 12) }
            }
        }
        Files.write(path, DiskAnalyticsFormat.frame(ByteArray(100)))
        RandomAccessFile(path.toFile(), "r").use { file ->
            assertFailsWith<EOFException> { DiskAnalyticsFormat.readFrame(file, 12) }
        }
    }
}
