package bosca.analytics.queue

import bosca.analytics.model.Events
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/** One append owner; only sealed files are visible to the consumer. */
internal class DiskAnalyticsWriter(
    private val directory: Path,
    private val json: Json,
    private val maxBytes: Long,
    private val maxEvents: Int,
    private val maxAgeMillis: Long,
) : Closeable {
    private val role = DiskAnalyticsRole(directory, "producer")
    private var sequence = 0L
    private var file: RandomAccessFile? = null
    private var created = 0L
    private var events = 0L
    private var records = 0L
    private var used = 0L
    private var failed = false

    init {
        try {
            val paths = Files.list(directory).use { it.toList() }
            sequence = paths.mapNotNull { it.fileName.toString().substringBefore('.').toLongOrNull() }.maxOrNull() ?: 0
            for (path in paths.filter { it.toString().endsWith(".open") }) recover(path)
            used = measureBytes()
        } catch (e: Exception) { role.close(); throw e }
    }

    private fun dataFile(extension: String) = directory.resolve("${sequence.toString().padStart(20, '0')}.$extension")
    private fun measureBytes(): Long = Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().substringAfterLast('.') in setOf("open", "segment", "quarantine") }.mapToLong {
            try { Files.size(it) } catch (_: NoSuchFileException) { 0L }
        }.sum()
    }
    private fun ensureCapacity(extra: Long) {
        if (used + extra + DiskAnalyticsFormat.FOOTER_BYTES > maxBytes) used = measureBytes()
        if (used + extra + DiskAnalyticsFormat.FOOTER_BYTES > maxBytes) throw DiskAnalyticsCapacityException(maxBytes)
    }
    private fun open(now: Long): RandomAccessFile {
        sequence++
        val opened = RandomAccessFile(dataFile("open").toFile(), "rw")
        try {
            DiskAnalyticsFormat.header(opened, sequence, now)
            opened.fd.sync(); DiskAnalyticsFormat.syncDirectory(directory)
            file = opened; created = now; events = 0; records = 0
            used += DiskAnalyticsFormat.HEADER_BYTES
            return opened
        } catch (e: Exception) { opened.close(); throw e }
    }
    fun append(batch: Events) {
        checkHealthy(); rollExpired()
        val now = System.currentTimeMillis()
        // Frame the whole batch and reserve its space first so a capacity rejection never seals part of it.
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        var required = 0L
        var segmentEvents = events.toInt()
        var segmentOpen = file != null
        var offset = 0
        while (offset < batch.events.size) {
            val count = minOf(maxEvents - segmentEvents, batch.events.size - offset)
            val part = batch.copy(events = batch.events.subList(offset, offset + count))
            val bytes = DiskAnalyticsFormat.frame(json.encodeToString(DiskAnalyticsRecord.serializer(), DiskAnalyticsRecord(part, now)).toByteArray(Charsets.UTF_8))
            if (!segmentOpen) { required += DiskAnalyticsFormat.HEADER_BYTES; segmentOpen = true }
            required += bytes.size; segmentEvents += count; offset += count
            if (segmentEvents >= maxEvents) { required += DiskAnalyticsFormat.FOOTER_BYTES; segmentEvents = 0; segmentOpen = false }
            frames += count to bytes
        }
        if (frames.isNotEmpty()) ensureCapacity(required)
        try {
            for ((count, bytes) in frames) {
                val current = file ?: open(now)
                current.write(bytes)
                used += bytes.size; events += count; records++
                if (events >= maxEvents) seal()
            }
            file?.let { it.fd.sync() }
        } catch (e: IOException) {
            failed = true
            try { file?.close() } finally { file = null }
            throw e
        }
    }
    fun checkHealthy() { check(!failed) { "Analytics spool writer requires restart after an I/O failure" } }
    fun rollExpired() {
        if (file != null && System.currentTimeMillis() - created >= maxAgeMillis) seal()
    }
    private fun seal() {
        val current = file ?: return
        try {
            DiskAnalyticsFormat.footer(current, events, records); current.fd.sync()
            current.close(); file = null
            Files.move(dataFile("open"), dataFile("segment"), ATOMIC_MOVE)
            DiskAnalyticsFormat.syncDirectory(directory)
            used += DiskAnalyticsFormat.FOOTER_BYTES
            events = 0; records = 0
        } catch (e: IOException) {
            failed = true
            try { file?.close() } finally { file = null }
            throw e
        }
    }
    private fun recover(path: Path) {
        try {
            RandomAccessFile(path.toFile(), "rw").use { recovering ->
                if (recovering.length() < DiskAnalyticsFormat.HEADER_BYTES) {
                    Files.delete(path); DiskAnalyticsFormat.syncDirectory(directory)
                    return
                }
                val id = DiskAnalyticsFormat.readHeader(recovering).first
                if (id.toString().padStart(20, '0') != path.fileName.toString().substringBefore('.')) throw CorruptAnalyticsSegmentException("Segment sequence mismatch")
                var position = DiskAnalyticsFormat.HEADER_BYTES
                var eventCount = 0L; var recordCount = 0L
                val counts = try { DiskAnalyticsFormat.readFooter(recovering) }
                    catch (_: CorruptAnalyticsSegmentException) { null }
                val end = if (counts == null) recovering.length() else recovering.length() - DiskAnalyticsFormat.FOOTER_BYTES
                while (position < end) {
                    recovering.seek(position)
                    if (end - position >= 4 && recovering.readInt() == -1) {
                        if (end - position < DiskAnalyticsFormat.FOOTER_BYTES) break
                        throw CorruptAnalyticsSegmentException("Damaged analytics footer")
                    }
                    recovering.seek(position)
                    val bytes = try { DiskAnalyticsFormat.readFrame(recovering, end) }
                        catch (_: EOFException) { break }
                        catch (e: CorruptAnalyticsSegmentException) {
                            // Without a footer the append was interrupted; only unsynced, unacknowledged bytes can follow.
                            if (counts != null) throw e
                            quarantineTail(path, recovering, position, e)
                            break
                        }
                    val record = json.decodeFromString(DiskAnalyticsRecord.serializer(), bytes.toString(Charsets.UTF_8))
                    eventCount += record.events.events.size; recordCount++; position = recovering.filePointer
                }
                counts?.let { DiskAnalyticsFormat.validateCounts(it, eventCount, recordCount) }
                recovering.setLength(position); recovering.seek(position)
                DiskAnalyticsFormat.footer(recovering, eventCount, recordCount); recovering.fd.sync()
            }
            Files.move(path, path.resolveSibling(path.fileName.toString().removeSuffix(".open") + ".segment"), ATOMIC_MOVE)
            DiskAnalyticsFormat.syncDirectory(directory)
        } catch (e: CorruptAnalyticsSegmentException) { quarantine(path, e) }
        catch (e: SerializationException) { quarantine(path, e) }
    }
    /** Preserves the bytes after the last valid frame of an interrupted segment before they are truncated. */
    private fun quarantineTail(path: Path, file: RandomAccessFile, position: Long, error: Exception) {
        val tail = path.resolveSibling("${path.fileName}.tail.quarantine")
        FileChannel.open(tail, CREATE, TRUNCATE_EXISTING, WRITE).use { output ->
            var transferred = 0L
            val length = file.length() - position
            while (transferred < length) transferred += file.channel.transferTo(position + transferred, length - transferred, output)
            output.force(true)
        }
        DiskAnalyticsFormat.syncDirectory(directory)
        LoggerFactory.getLogger(javaClass).error("Preserved damaged tail of interrupted analytics segment {} as {}", path, tail, error)
    }
    private fun quarantine(path: Path, error: Exception) {
        Files.move(path, path.resolveSibling("${path.fileName}.quarantine"), ATOMIC_MOVE)
        DiskAnalyticsFormat.syncDirectory(directory)
        LoggerFactory.getLogger(javaClass).error("Preserved corrupt analytics segment {}", path, error)
    }
    override fun close() {
        try { seal() } finally { role.close() }
    }
}
