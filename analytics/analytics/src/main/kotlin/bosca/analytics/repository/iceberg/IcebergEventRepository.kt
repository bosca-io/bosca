package bosca.analytics.repository.iceberg

import bosca.analytics.model.Events
import bosca.analytics.repository.EventRepository
import bosca.analytics.transform.EventsTransform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.protobuf.ProtoBuf
import org.apache.iceberg.Table
import org.apache.iceberg.data.IcebergGenerics
import org.apache.iceberg.data.Record
import org.jetbrains.annotations.TestOnly
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class IcebergEventRepository(
    private val table: Table,
    private val transform: EventsTransform<List<Record>>,
    private val localPath: String,
    private val protoBuf: ProtoBuf
) : EventRepository {

    private val appendMutex = Mutex()
    private val fileMutex = Mutex()
    private val appender = DataFileAppender(table)
    private var file: LocalEventsFile? = null
    private val backgroundJob = SupervisorJob()
    private val backgroundScope = CoroutineScope(backgroundJob + Dispatchers.IO)

    init {
        val localPath = File(localPath)
        if (!localPath.exists()) localPath.mkdirs()
        val previousFiles = localPath.walkTopDown().maxDepth(1).drop(1).toList()
        backgroundScope.launch {
            appendPreviousFiles(previousFiles)
            while (isActive) {
                try {
                    maybeAppend(sync = true, force = false)
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
            appendMutex.withLock {
                log.info("Appending previous file: ${file.name}")
                try {
                    newLocalEventFile(file.name).append()
                } catch (e: Exception) {
                    log.error("Failed to append previous file: ${file.name}", e)
                }
            }
        }
    }

    internal suspend fun maybeAppend(sync: Boolean, force: Boolean): Job? = appendMutex.withLock {
        var appendJob: Job? = null
        val current = file
        if (current != null && current.shouldAppend(force)) {
            fileMutex.withLock {
                file = newLocalEventFile()
            }
            if (sync) {
                current.append()
            } else {
                appendJob = backgroundScope.launch {
                    appendMutex.withLock {
                        current.append()
                    }
                }
            }
        }
        appendJob
    }

    override suspend fun process(events: Events) {
        try {
            fileMutex.withLock {
                val current = file ?: newLocalEventFile().also { file = it }
                current.addEvents(events)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to process events", e)
        }
    }

    override suspend fun flush() {
        maybeAppend(sync = true, force = true)
    }

    /** Stops periodic and asynchronous rollover work before its local staging directory is removed. */
    suspend fun shutdown() {
        backgroundJob.cancelAndJoin()
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

        private val log = LoggerFactory.getLogger(IcebergEventRepository::class.java)
    }
}
