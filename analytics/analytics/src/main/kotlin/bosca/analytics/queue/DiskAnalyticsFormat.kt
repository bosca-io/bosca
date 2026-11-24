package bosca.analytics.queue

import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.zip.CRC32C

/** Checksummed frames and counts for append-only segment files. */
internal object DiskAnalyticsFormat {
    const val HEADER_BYTES = 28L
    const val FOOTER_BYTES = 24L
    const val FRAME_HEADER_BYTES = 12
    private const val MAGIC = 0x424F53434153504CL
    const val VERSION = 3

    fun checksum(bytes: ByteArray, length: Int = bytes.size): Int = CRC32C().apply { update(bytes, 0, length) }.value.toInt()
    fun frame(bytes: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(8).putInt(bytes.size).putInt(checksum(bytes)).array()
        return ByteBuffer.allocate(FRAME_HEADER_BYTES + bytes.size).put(header).putInt(checksum(header)).put(bytes).array()
    }
    fun header(file: RandomAccessFile, sequence: Long, created: Long) {
        file.writeLong(MAGIC); file.writeInt(VERSION); file.writeLong(sequence); file.writeLong(created)
    }
    fun readHeader(file: RandomAccessFile): Pair<Long, Long> {
        file.seek(0)
        if (file.readLong() != MAGIC || file.readInt() != VERSION) throw CorruptAnalyticsSegmentException("Unsupported analytics segment format")
        return file.readLong() to file.readLong()
    }
    fun footer(file: RandomAccessFile, events: Long, records: Long) {
        val counts = ByteBuffer.allocate(16).putLong(events).putLong(records).array()
        file.writeInt(-1); file.writeInt(checksum(counts)); file.write(counts)
    }
    fun readFooter(file: RandomAccessFile): Pair<Long, Long> {
        if (file.length() < HEADER_BYTES + FOOTER_BYTES) throw CorruptAnalyticsSegmentException("Truncated analytics segment")
        file.seek(file.length() - FOOTER_BYTES)
        if (file.readInt() != -1) throw CorruptAnalyticsSegmentException("Missing analytics segment footer")
        val crc = file.readInt(); val counts = ByteArray(16); file.readFully(counts)
        if (crc != checksum(counts)) throw CorruptAnalyticsSegmentException("Analytics footer checksum mismatch")
        val buffer = ByteBuffer.wrap(counts)
        val events = buffer.long; val records = buffer.long
        if (events < 0 || records < 0) throw CorruptAnalyticsSegmentException("Invalid analytics segment counts")
        return events to records
    }
    fun validateCounts(expected: Pair<Long, Long>, events: Long, records: Long) {
        if (expected.first != events || expected.second != records) throw CorruptAnalyticsSegmentException("Analytics segment counts mismatch")
    }
    fun readFrame(file: RandomAccessFile, end: Long): ByteArray {
        val header = ByteArray(FRAME_HEADER_BYTES); file.readFully(header)
        val buffer = ByteBuffer.wrap(header); val length = buffer.int; val expected = buffer.int
        if (buffer.int != checksum(header, 8)) throw CorruptAnalyticsSegmentException("Analytics frame header checksum mismatch")
        if (length <= 0) throw CorruptAnalyticsSegmentException("Invalid analytics frame length: $length")
        if (length.toLong() > end - file.filePointer) throw EOFException("Incomplete analytics frame")
        val bytes = ByteArray(length); file.readFully(bytes)
        if (checksum(bytes) != expected) throw CorruptAnalyticsSegmentException("Analytics frame checksum mismatch")
        return bytes
    }
    fun segmentPath(directory: Path, sequence: Long): Path = directory.resolve("${sequence.toString().padStart(20, '0')}.segment")
    fun syncDirectory(directory: Path) { FileChannel.open(directory, READ).use { it.force(true) } }
}
