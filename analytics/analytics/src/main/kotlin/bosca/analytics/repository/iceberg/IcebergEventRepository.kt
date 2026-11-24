package bosca.analytics.repository.iceberg

import bosca.analytics.model.Events
import bosca.analytics.repository.EventRepository
import bosca.analytics.transform.EventsTransform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf
import bosca.serialization.UUID
import org.apache.iceberg.Table
import org.apache.iceberg.data.IcebergGenerics
import org.apache.iceberg.data.Record
import org.jetbrains.annotations.TestOnly
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Stores analytics events in an Iceberg [table].
 *
 * [store] group-commits: concurrent calls share one data file and one table commit. A commit starts once
 * [storeConcurrency] calls are waiting, once the waiting calls hold [commitMaxRecords] events, or
 * [commitInterval] after the first waiting call, whichever comes first.
 */
class IcebergEventRepository(
    private val table: Table,
    private val transform: EventsTransform<List<Record>>,
    private val localPath: String,
    private val protoBuf: ProtoBuf,
    private val commitInterval: Duration = Duration.ZERO,
    private val commitMaxRecords: Int = Int.MAX_VALUE,
    private val storeConcurrency: Int = Int.MAX_VALUE,
) : EventRepository {

    private class StoreRequest(val batches: List<Events>) {
        val events: Int = batches.sumOf { it.events.size }
        val stored = CompletableDeferred<Unit>()
    }

    private val mutex = Mutex()
    private val appender = DataFileAppender(table)
    private var file: LocalEventsFile? = null
    private var appendFailed = false
    private val backgroundJob = SupervisorJob()
    private val backgroundScope = CoroutineScope(backgroundJob + Dispatchers.IO)
    private val storeRequests = Channel<StoreRequest>(Channel.UNLIMITED)

    init {
        require(commitMaxRecords > 0 && storeConcurrency > 0 && !commitInterval.isNegative())
        backgroundScope.launch { commitStoreRequests() }
        val localPath = File(localPath)
        if (!localPath.exists()) localPath.mkdirs()
        val previousFiles = localPath.walkTopDown().maxDepth(1).drop(1).filter { it.isFile }.toList()
        backgroundScope.launch {
            appendPreviousFiles(previousFiles)
            while (isActive) {
                try {
                    flush(force = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Failed to roll file during periodic check", e)
                }
                delay(60_000.milliseconds)
            }
        }
    }

    private fun newLocalEventFile(filename: String? = null) = LocalEventsFile(table, appender, transform, localPath, protoBuf, filename)

    internal suspend fun appendPreviousFiles(files: List<File>) {
        // cleanup
        files.filter { it.name.endsWith(".parquet") }.forEach {
            log.info("Deleting previous file: ${it.name}")
            it.delete()
        }
        files.filter { !it.name.endsWith(".parquet") }.forEach { file ->
            mutex.withLock {
                log.info("Appending previous file: ${file.name}")
                try {
                    newLocalEventFile(file.name).append()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Failed to append previous file: ${file.name}", e)
                }
            }
        }
    }

    private suspend fun appendCurrent(force: Boolean) {
        val current = file?.takeIf { appendFailed || it.shouldAppend(force) } ?: return
        try {
            current.append()
            file = null
            appendFailed = false
        } catch (e: Exception) {
            appendFailed = true
            throw e
        }
    }

    override suspend fun process(events: Events) = mutex.withLock {
        if (appendFailed) appendCurrent(force = true)
        val current = file ?: newLocalEventFile().also { file = it }
        current.addEvents(events)
    }

    override suspend fun flush() = flush(force = true)

    internal suspend fun flush(force: Boolean) = mutex.withLock { appendCurrent(force) }

    /**
     * Commits [batches] in a data file shared with concurrent callers, bypassing the staged file used by
     * [process]. A transform failure fails only its own call; a write or commit failure fails every call in
     * the group, and none of their events remains buffered. A local file left by a crash ends in `.parquet`
     * and is deleted at startup.
     */
    override suspend fun store(batches: List<Events>) {
        if (batches.all { it.events.isEmpty() }) return
        val request = StoreRequest(batches)
        storeRequests.trySend(request).getOrElse { throw IllegalStateException("Iceberg event repository is shut down", it) }
        request.stored.await()
    }

    private suspend fun commitStoreRequests() {
        for (first in storeRequests) {
            val group = mutableListOf(first)
            var events = first.events
            val deadline = TimeSource.Monotonic.markNow() + commitInterval
            try {
                while (group.size < storeConcurrency && events < commitMaxRecords) {
                    val next = storeRequests.tryReceive().getOrNull()
                    if (next != null) {
                        group += next
                        events += next.events
                        continue
                    }
                    val remaining = -deadline.elapsedNow()
                    if (!remaining.isPositive()) break
                    delay(minOf(remaining, STORE_POLL_INTERVAL))
                }
                commit(group)
            } catch (e: CancellationException) {
                val shutdown = IllegalStateException("Iceberg event repository is shut down", e)
                group.forEach { it.stored.completeExceptionally(shutdown) }
                throw e
            }
        }
    }

    private suspend fun commit(group: List<StoreRequest>) {
        val transformed = group.mapNotNull { request ->
            try {
                request to request.batches.flatMap { transform.transform(it) }
            } catch (e: Exception) {
                // Only the committer's own cancellation stops it; any other failure belongs to the caller.
                currentCoroutineContext().ensureActive()
                request.stored.completeExceptionally(e)
                null
            }
        }
        if (transformed.isEmpty()) return
        val filename = "events-${UUID.random()}.parquet"
        val localFile = File(localPath, filename)
        try {
            withContext(Dispatchers.IO) {
                ParquetWriter(table, localFile).use { writer ->
                    for ((_, records) in transformed) writer.addAll(records)
                    mutex.withLock { appender.append(writer, filename) }
                }
            }
            transformed.forEach { (request, _) -> request.stored.complete(Unit) }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            transformed.forEach { (request, _) -> request.stored.completeExceptionally(e) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { localFile.delete() }
        }
    }

    /** Stops periodic and asynchronous rollover work before its local staging directory is removed. */
    suspend fun shutdown() {
        storeRequests.close()
        backgroundJob.cancelAndJoin()
        val shutdown = IllegalStateException("Iceberg event repository is shut down")
        while (true) storeRequests.tryReceive().getOrNull()?.stored?.completeExceptionally(shutdown) ?: break
    }

    @TestOnly
    fun getEvents(): Flow<Events> {
        return flow {
            // TODO: offset/limit semantics
            IcebergGenerics.read(table).build().use {
                val reader = Reader(it)
                while (reader.hasNext()) {
                    emit(reader.next())
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    companion object {
        private val STORE_POLL_INTERVAL = 10.milliseconds
        private val log = LoggerFactory.getLogger(IcebergEventRepository::class.java)
    }
}
