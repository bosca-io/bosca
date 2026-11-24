package bosca.analytics.queue

import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Counts retained sealed events and all data bytes without parsing active payloads or tracking claims. */
internal class DiskAnalyticsStatusReader(private val directory: Path, private val maxBytes: Long) {
    fun read(): AnalyticsEventQueueStatus {
        var pending = 0L; var bytes = 0L; var quarantined = 0L
        var oldest: Long? = null
        Files.newDirectoryStream(directory).use { paths ->
            for (path in paths) {
                val extension = path.fileName.toString().substringAfterLast('.')
                if (extension !in setOf("segment", "open", "quarantine")) continue
                try {
                    bytes += Files.size(path)
                    if (extension == "quarantine") { quarantined++; continue }
                    if (extension != "segment") continue
                    RandomAccessFile(path.toFile(), "r").use { file ->
                        val header = DiskAnalyticsFormat.readHeader(file)
                        if (header.first.toString().padStart(20, '0') != path.fileName.toString().substringBefore('.')) throw CorruptAnalyticsSegmentException("Segment sequence mismatch")
                        val events = DiskAnalyticsFormat.readFooter(file).first
                        pending += events
                        if (events > 0) oldest = minOf(oldest ?: header.second, header.second)
                    }
                } catch (_: NoSuchFileException) { }
                catch (e: FileNotFoundException) { if (!Files.notExists(path)) throw e }
                catch (_: CorruptAnalyticsSegmentException) { quarantined++ }
            }
        }
        return AnalyticsEventQueueStatus(pending, bytes, maxBytes, quarantined,
            oldest?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) })
    }
}
