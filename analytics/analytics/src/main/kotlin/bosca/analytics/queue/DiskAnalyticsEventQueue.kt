package bosca.analytics.queue

import bosca.analytics.model.Events
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Append-only durable spool shared by one producer and one consumer process. */
class DiskAnalyticsEventQueue(
    directory: Path,
    private val json: Json,
    private val maxBytes: Long = 1_073_741_824,
    private val maxSegmentEvents: Int = 10_000,
    private val maxSegmentAge: Duration = 1.seconds,
) : AnalyticsEventQueue {
    private val directory = directory.toAbsolutePath().normalize()
    private val producerMutex = Mutex()
    private val consumerMutex = Mutex()
    private val statusReader = DiskAnalyticsStatusReader(this.directory, maxBytes)
    @Volatile private var prepared = false
    private var writer: DiskAnalyticsWriter? = null
    private var reader: DiskAnalyticsReader? = null
    @Volatile private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        require(maxBytes > 0 && maxSegmentEvents > 0)
        require(maxSegmentAge.isPositive() && maxSegmentAge.isFinite() && maxSegmentAge.inWholeMilliseconds > 0)
    }
    private val maintenance = scope.launch {
        while (isActive) {
            delay(minOf(maxSegmentAge.inWholeMilliseconds, 1000))
            try { producerMutex.withLock { writer?.rollExpired() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LoggerFactory.getLogger(javaClass).error("Analytics spool maintenance failed", e) }
        }
    }
    private suspend fun prepare() {
        check(!closed) { "Analytics spool is closed" }
        if (!prepared) {
            Files.createDirectories(directory)
            prepared = true
        }
    }
    private fun checkFormat() {
        check(paths().none { it.fileName.toString().let { name -> name.endsWith(".event") || name.endsWith(".ack") || ".processing." in name } }) {
            "Analytics spool contains an unsupported per-event file format: $directory"
        }
    }
    private fun paths(): List<Path> = Files.list(directory).use { it.toList() }
    private fun consumer(): DiskAnalyticsReader = reader ?: run {
        checkFormat()
        DiskAnalyticsReader(this, directory, json).also { reader = it }
    }
    private fun producer(): DiskAnalyticsWriter = writer ?: run {
        checkFormat()
        DiskAnalyticsWriter(directory, json, maxBytes, maxSegmentEvents, maxSegmentAge.inWholeMilliseconds).also { writer = it }
    }
    /** Acquires the producer role and recovers accepted active data before the collector accepts traffic. */
    suspend fun startProducer() = withContext(Dispatchers.IO) {
        producerMutex.withLock { prepare(); producer().checkHealthy() }
    }
    override suspend fun enqueue(events: Events) = withContext(Dispatchers.IO) {
        producerMutex.withLock {
            prepare()
            producer().append(events)
        }
    }
    override suspend fun receive(timeout: Duration): AnalyticsEventDelivery? = receiveBatch(1, timeout).firstOrNull()
    override suspend fun receiveBatch(limit: Int, timeout: Duration): List<AnalyticsEventDelivery> {
        require(limit > 0)
        val started = TimeSource.Monotonic.markNow()
        do {
            val deliveries = withContext(Dispatchers.IO) { consumerMutex.withLock {
                prepare()
                listOfNotNull(consumer().receive())
            } }
            if (deliveries.isNotEmpty() || started.elapsedNow() >= timeout) return deliveries
            delay(minOf(100, (timeout - started.elapsedNow()).inWholeMilliseconds.coerceAtLeast(1)))
        } while (true)
    }
    private fun owned(deliveries: List<AnalyticsEventDelivery>): List<DiskAnalyticsDelivery> = deliveries.map {
        check(it is DiskAnalyticsDelivery && it.owner === this) { "Analytics delivery belongs to another queue" }; it
    }.also { require(it.map { delivery -> delivery.segment }.distinct().size == it.size) { "Duplicate analytics delivery" } }
    override suspend fun acknowledgeBatch(deliveries: List<AnalyticsEventDelivery>) = withContext(Dispatchers.IO) {
        consumerMutex.withLock { prepare(); if (deliveries.isNotEmpty()) {
            val accepted = owned(deliveries)
            consumer().acknowledge(accepted)
        } }
    }
    override suspend fun retryBatch(deliveries: List<AnalyticsEventDelivery>, delay: Duration) = withContext(Dispatchers.IO) {
        consumerMutex.withLock { prepare(); if (deliveries.isNotEmpty()) {
            val accepted = owned(deliveries)
            consumer().retry(accepted, delay)
        } }
    }
    internal suspend fun renew(delivery: DiskAnalyticsDelivery) = withContext(Dispatchers.IO) {
        consumerMutex.withLock { prepare(); consumer().validate(listOf(delivery)) }
    }
    override suspend fun status(): AnalyticsEventQueueStatus = withContext(Dispatchers.IO) {
        prepare()
        statusReader.read()
    }
    override suspend fun checkReadiness() = withContext(Dispatchers.IO) {
        producerMutex.withLock {
            prepare(); checkFormat(); writer?.checkHealthy()
            val probe = directory.resolve("probe-${UUID.randomUUID()}.tmp")
            try { RandomAccessFile(probe.toFile(), "rw").use { it.write(1); it.fd.sync() } }
            finally { Files.deleteIfExists(probe) }
            DiskAnalyticsFormat.syncDirectory(directory)
        }
        check(status().storageBytes < maxBytes) { "Analytics spool capacity exceeded: $maxBytes bytes" }
    }
    override suspend fun shutdown() = withContext(NonCancellable + Dispatchers.IO) {
        maintenance.cancelAndJoin()
        producerMutex.withLock { consumerMutex.withLock {
            if (!closed) {
                closed = true
                try { writer?.close() } finally { try { reader?.close() } finally { scope.cancel() } }
            }
        } }
    }
}
