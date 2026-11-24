package bosca.analytics.queue

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A process owns the consumer role; individual sealed files remain on disk until acknowledged. */
internal class DiskAnalyticsReader(
    private val owner: DiskAnalyticsEventQueue,
    private val directory: Path,
    private val json: Json,
) : Closeable {
    private val role = DiskAnalyticsRole(directory, "consumer")
    private val claimed = mutableMapOf<Long, DiskAnalyticsDelivery>()
    private val deferred = mutableMapOf<Long, TimeMark>()
    private var paths: DirectoryStream<Path>? = null
    private var cursor: Iterator<Path>? = null

    fun receive(): DiskAnalyticsDelivery? {
        var opened = false
        while (true) {
            val iterator = cursor ?: run {
                if (opened) return null
                val stream = Files.newDirectoryStream(directory, "*.segment")
                paths = stream
                opened = true
                stream.iterator().also { cursor = it }
            }
            while (iterator.hasNext()) {
                val path = iterator.next()
                val id = path.fileName.toString().substringBefore('.').toLongOrNull()
                    ?: throw CorruptAnalyticsSegmentException("Invalid analytics segment name: ${path.fileName}")
                if (id in claimed || deferred[id]?.hasNotPassedNow() == true) continue
                deferred.remove(id)
                try {
                    val batches = RandomAccessFile(path.toFile(), "r").use { file ->
                        if (DiskAnalyticsFormat.readHeader(file).first != id) throw CorruptAnalyticsSegmentException("Segment sequence mismatch")
                        val counts = DiskAnalyticsFormat.readFooter(file)
                        val end = file.length() - DiskAnalyticsFormat.FOOTER_BYTES
                        file.seek(DiskAnalyticsFormat.HEADER_BYTES)
                        buildList {
                            while (file.filePointer < end) {
                                val bytes = DiskAnalyticsFormat.readFrame(file, end)
                                add(json.decodeFromString(DiskAnalyticsRecord.serializer(), bytes.toString(Charsets.UTF_8)).events)
                            }
                        }.also { batches -> DiskAnalyticsFormat.validateCounts(counts, batches.sumOf { it.events.size.toLong() }, batches.size.toLong()) }
                    }
                    return DiskAnalyticsDelivery(owner, id, batches).also { claimed[id] = it }
                } catch (e: CorruptAnalyticsSegmentException) { quarantine(path, e) }
                catch (e: SerializationException) { quarantine(path, e) }
                catch (e: EOFException) { quarantine(path, e) }
                catch (e: FileNotFoundException) {
                    if (!Files.notExists(path)) { deferred[id] = TimeSource.Monotonic.markNow() + 1.seconds; throw e }
                } catch (e: IOException) { deferred[id] = TimeSource.Monotonic.markNow() + 1.seconds; throw e }
            }
            closeCursor()
        }
    }
    private fun quarantine(path: Path, error: Exception) {
        Files.move(path, path.resolveSibling("${path.fileName}.quarantine"), ATOMIC_MOVE)
        DiskAnalyticsFormat.syncDirectory(directory)
        LoggerFactory.getLogger(javaClass).error("Preserved corrupt analytics segment {}", path, error)
    }
    fun validate(deliveries: List<DiskAnalyticsDelivery>) {
        for (delivery in deliveries) check(claimed[delivery.segment] === delivery) { "Analytics delivery no longer owns its segment" }
    }
    fun acknowledge(deliveries: List<DiskAnalyticsDelivery>) {
        validate(deliveries)
        try {
            for (delivery in deliveries) Files.delete(DiskAnalyticsFormat.segmentPath(directory, delivery.segment))
            DiskAnalyticsFormat.syncDirectory(directory)
        } finally { deliveries.forEach { claimed.remove(it.segment) } }
    }
    fun retry(deliveries: List<DiskAnalyticsDelivery>, delay: Duration) {
        validate(deliveries)
        for (delivery in deliveries) {
            claimed.remove(delivery.segment)
            deferred[delivery.segment] = TimeSource.Monotonic.markNow() + delay.coerceAtLeast(Duration.ZERO)
        }
    }
    private fun closeCursor() { paths?.close(); paths = null; cursor = null }
    override fun close() {
        try { closeCursor(); claimed.clear(); deferred.clear() } finally { role.close() }
    }
}
