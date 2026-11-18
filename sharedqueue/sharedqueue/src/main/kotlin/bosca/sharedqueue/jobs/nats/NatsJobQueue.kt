package bosca.sharedqueue.jobs.nats

import bosca.cache.withRequestCache
import bosca.core.annotations.Internal
import bosca.db.ConnectionManagerCallback
import bosca.db.connectionOrNull
import bosca.db.withConnectionManager
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.JobsDispatcher
import bosca.sharedqueue.jobs.LockAcquisitionException
import bosca.sharedqueue.jobs.SerializedJob
import bosca.sharedqueue.jobs.deserialize
import bosca.sharedqueue.jobs.newJobLock
import bosca.sharedqueue.jobs.serialize
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamSubscription
import io.nats.client.KeyValue
import io.nats.client.Message
import io.nats.client.MessageTtl
import io.nats.client.PublishOptions
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.KeyValueConfiguration
import io.nats.client.api.KeyValueOperation
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StreamConfiguration
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class NatsJobQueue(
    private val queue: String,
    private val nats: NatsConnectionPool,
    private val json: Json,
    private val distributedLockFactory: DistributedLockFactory,
    processExpiredJobs: Boolean = true
) : JobQueue {

    override val name: String get() = queue

    private val streamName = "jobs-$queue"
    private val subject = "jobs.$queue"

    private var _js: JetStream? = null
    private var _kv: KeyValue? = null
    private var _scheduledKv: KeyValue? = null
    private var _sub: JetStreamSubscription? = null
    private val initMutex = Mutex()

    init {
        if (processExpiredJobs) {
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(JobsDispatcher) {
                ensureInitialized()
                while (true) {
                    try {
                        checkForExpiredJobs()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Failed to evict expired items: {}", e.message)
                    }
                    delay(30_000.milliseconds)
                }
            }
        }
    }

    private suspend fun ensureInitialized() {
        if (_sub != null) return
        initMutex.withLock {
            if (_sub != null) return
            withContext(Dispatchers.IO) {
                val connection = nats.systemConnection()
                val jsm = connection.jetStreamManagement()
                try {
                    jsm.getStreamInfo(streamName)
                } catch (e: Exception) {
                    log.info("Stream {} not found, creating: {}", streamName, e.message)
                    jsm.addStream(
                        StreamConfiguration.builder()
                            .name(streamName)
                            .subjects(subject)
                            .allowMessageTtl(true)
                            .retentionPolicy(RetentionPolicy.WorkQueue)
                            .duplicateWindow(java.time.Duration.ofMinutes(10))
                            .build()
                    )
                }
                _js = connection.jetStream()
                _kv = initializeKeyValue(connection, "job-state-$queue")
                _scheduledKv = initializeKeyValue(connection, "job-scheduled-$queue")

                val js = _js ?: error("JetStream not initialized")
                val consumerConfig = ConsumerConfiguration.builder()
                    .durable("jobs-$queue")
                    .ackWait(java.time.Duration.ofMinutes(2))
                    .build()
                _sub = js.subscribe(subject, PullSubscribeOptions.builder().configuration(consumerConfig).build())
            }
        }
    }

    private fun initializeKeyValue(connection: Connection, bucket: String): KeyValue {
        val management = connection.keyValueManagement()
        val keyValue = try {
            connection.keyValue(bucket)
        } catch (e: Exception) {
            log.info("KeyValue {} not found, creating: {}", bucket, e.message)
            management.create(
                KeyValueConfiguration.builder()
                    .name(bucket)
                    .limitMarker(KEY_VALUE_MARKER_TTL)
                    .build()
            )
            return connection.keyValue(bucket)
        }

        val status = management.getStatus(bucket)
        if (status.limitMarkerTtl != KEY_VALUE_MARKER_TTL) {
            log.info("Enabling {} limit markers for KeyValue {}", KEY_VALUE_MARKER_TTL, bucket)
            management.update(
                KeyValueConfiguration.builder(status.configuration)
                    .limitMarker(KEY_VALUE_MARKER_TTL)
                    .build()
            )
        }
        return keyValue
    }

    private suspend fun js(): JetStream {
        ensureInitialized(); return _js ?: error("JetStream not initialized")
    }

    private suspend fun kv(): KeyValue {
        ensureInitialized(); return _kv ?: error("KeyValue not initialized")
    }

    private suspend fun scheduledKv(): KeyValue {
        ensureInitialized(); return _scheduledKv ?: error("Scheduled KeyValue not initialized")
    }

    private suspend fun sub(): JetStreamSubscription {
        ensureInitialized(); return _sub ?: error("JetStream Subscription not initialized")
    }

    private fun Job.verifyLocked() {
        val lock = lock ?: error("Job $id has no lock")
        if (!lock.isHeld) error("Job $id lock is not held")
    }

    private val Job.isLockHeld: Boolean
        get() {
            val lock = lock ?: return false
            return lock.isHeld
        }

    override suspend fun <T> getJob(id: UUID, block: suspend (Job?) -> T): T = withContext(JobsDispatcher) {
        internalGetJob(id, 60_000, releaseLock = true, wait = true, block = block)
    }

    @OptIn(Internal::class)
    private suspend fun <T> internalGetJob(id: UUID, lockTimeout: Long, releaseLock: Boolean, wait: Boolean, block: suspend (Job?) -> T): T {
        var lock: DistributedLock? = null
        var handedOff = false
        try {
            val result = withContext(JobsDispatcher) {
                val acquiredLock = newJobLock(distributedLockFactory, id, lockTimeout, wait)
                lock = acquiredLock
                val entry = try {
                    kv().get(id.toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Failed to get job $id", e)
                    null
                }
                if (entry == null) error("Job $id not found")
                val serializedJob = entry.valueAsString?.let { json.decodeFromString(SerializedJob.serializer(), it) }
                if (serializedJob == null) error("Job $id not found")
                val job = serializedJob.deserialize()
                job.lock = acquiredLock
                block(job)
            }
            // Transfer ownership only after the dispatcher handoff has completed without cancellation.
            handedOff = !releaseLock && result != null
            return result
        } finally {
            if (!handedOff) withContext(NonCancellable) { lock?.release() }
        }
    }

    override suspend fun setJob(job: Job): Unit = withContext(JobsDispatcher) {
        job.verifyLocked()
        kv().put(job.id.toString(), json.encodeToString(SerializedJob.serializer(), job.serialize()))
    }

    override suspend fun checkin(job: Job, lockRenew: Long) = withContext(JobsDispatcher) {
        val lock = job.lock ?: return@withContext false
        if (!lock.isHeld || !lock.renew(lockRenew)) return@withContext false
        (job.message as? Message)?.inProgress()
        job.modified = OffsetDateTime.now()
        setJob(job)
        true
    }

    override suspend fun setDefinition(job: Job, definition: JsonElement) = withContext(JobsDispatcher) {
        job.definition = definition
        setJob(job)
    }

    override suspend fun markFailed(job: Job, exception: Exception, retry: Boolean): Unit = withContext(JobsDispatcher) {
        var job = job
        try {
            if (!job.isLockHeld) {
                val renewed = job.lock?.renew(60_000) ?: false
                if (!renewed) {
                    log.error("Job ${job.id} not locked, trying to acquire lock")
                    job = internalGetJob(job.id, 60_000, releaseLock = false, wait = true) { it ?: error("Job ${job.id} not found") }
                }
            }
            val msg = job.message as? Message
            job.failures++
            // FAILED is retryable; the final failure (no more retries) is the terminal
            // FAILED_AND_COMPLETE — which counts as complete for a parent and fires runOnFailure children.
            val terminal = !retry || job.failures >= job.maxFailures
            job.setStatus(if (terminal) JobStatus.FAILED_AND_COMPLETE else JobStatus.FAILED)
            if (terminal) {
                log.warn("Marking job ${job.id} - ${job.executor} - ${job.executorName} - as failed, not retrying")
                kv().purge(job.id.toString(), TERMINAL_PURGE_TTL)
                msg?.ack()
            } else {
                setJob(job)
                log.warn("Marking job ${job.id} - ${job.executor} - ${job.executorName} - as failed, retrying")
                msg?.nakWithDelay(java.time.Duration.ofSeconds((30 * job.failures).toLong()))
            }
            log.warn("Marked job ${job.id} - ${job.executor} - ${job.executorName} - as failed: ${exception.message}")
        } finally {
            job.lock?.release()
        }
        withRequestCache {
            withConnectionManager {
                job.callbacks.forEach {
                    it.newListener().onStatusChanged(job, job.status, exception.stackTraceToString())
                }
            }
        }
    }

    override suspend fun markComplete(job: Job): Unit = withContext(JobsDispatcher) {
        var job = job
        try {
            if (!job.isLockHeld) {
                val renewed = job.lock?.renew(60_000) ?: false
                if (!renewed) {
                    log.error("Job ${job.id} not locked, trying to acquire lock")
                    job = internalGetJob(job.id, 60_000, releaseLock = false, wait = true) { it ?: error("Job ${job.id} not found") }
                }
            }
            val msg = job.message as? Message
            job.setStatus(JobStatus.COMPLETE)
            if (job.isFullyComplete()) {
                kv().purge(job.id.toString(), TERMINAL_PURGE_TTL)
            } else {
                setJob(job)
            }
            msg?.ack()
            log.info("Marked job ${job.id} - ${job.executor} - ${job.executorName} - Fully Complete = ${job.isFullyComplete()} as complete")
        } finally {
            job.lock?.release()
        }
        withRequestCache {
            withConnectionManager {
                job.callbacks.forEach {
                    it.newListener().onStatusChanged(job, JobStatus.COMPLETE)
                }
            }
        }
    }

    override suspend fun markCancelled(id: UUID): Unit = withContext(JobsDispatcher) {
        val failures = buildList {
            try {
                kv().purge(id.toString(), TERMINAL_PURGE_TTL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                add(e)
            }
            try {
                scheduledKv().purge(id.toString(), TERMINAL_PURGE_TTL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                add(e)
            }
        }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    override suspend fun checkForExpiredJobs(time: Long) = withContext(JobsDispatcher) {
        processExpiredJobs(time)
    }

    override suspend fun expireAllJobs() = withContext(JobsDispatcher) {
        processExpiredJobs(Long.MAX_VALUE, redrivePublished = true)
    }

    private fun SerializedJob.needsPublication(time: Long, redrivePublished: Boolean): Boolean =
        modified.toEpochSecond() * 1000 <= time && when (status) {
            JobStatus.UNINITIALIZED -> true
            JobStatus.PENDING, JobStatus.RUNNING -> redrivePublished
            else -> false
        }

    @OptIn(Internal::class)
    private suspend fun processExpiredJobs(time: Long, redrivePublished: Boolean = false) = withContext(Dispatchers.IO) {
        val k = kv()
        val keys = try {
            k.keys()
        } catch (e: Exception) {
            log.warn("Failed to list keys for expired job scan in queue {}: {}", queue, e.message)
            return@withContext
        }
        var count = 0L
        for (key in keys) {
            try {
                val entry = k.get(key) ?: continue
                val value = entry.valueAsString ?: continue
                val serializedJob = json.decodeFromString(SerializedJob.serializer(), value)
                if (!serializedJob.needsPublication(time, redrivePublished)) continue
                val lock = try {
                    newJobLock(distributedLockFactory, serializedJob.id, 60_000, wait = false)
                } catch (_: LockAcquisitionException) {
                    continue
                }
                try {
                    // A worker may have advanced or completed this job since the scan read.
                    val currentEntry = k.get(key) ?: continue
                    val currentValue = currentEntry.valueAsString ?: continue
                    val current = json.decodeFromString(SerializedJob.serializer(), currentValue)
                    if (!current.needsPublication(time, redrivePublished)) continue
                    if (current.status == JobStatus.UNINITIALIZED) {
                        publish(current, currentEntry.revision)
                    } else {
                        // Explicit redrive preserves intentional scheduling and starts a new
                        // publication attempt. Ordinary reconciliation never enters this path.
                        val scheduled = scheduledKv().get(key)
                        if (scheduled != null && scheduled.operation == KeyValueOperation.PUT &&
                            (scheduled.valueAsString?.toLongOrNull() ?: 0L) > System.currentTimeMillis()
                        ) continue
                        val unpublished = current.copy(status = JobStatus.UNINITIALIZED, modified = OffsetDateTime.now())
                        val revision = k.put(key, json.encodeToString(SerializedJob.serializer(), unpublished))
                        publish(unpublished, revision)
                    }
                    log.info("Recovered publication for job {} (executor: {})", key, current.executor)
                    count++
                } finally {
                    withContext(NonCancellable) { lock.release() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to process expired job: $key", e)
            }
        }
        if (count > 0) {
            log.info("Re-queued {} stale jobs in queue: {}", count, queue)
        }
    }

    override suspend fun enqueue(job: Job): UUID {
        if (job.message != null) error("Cannot enqueue job with message: ${job.message}")
        job.setStatus(JobStatus.PENDING, true)
        job.queueName = name
        val serializedJob = job.serialize()
        dbRunAfterCommit { enqueue(job, serializedJob) }
        return serializedJob.id
    }

    override suspend fun enqueueIfAbsent(job: Job): UUID {
        require(job.id != UUID.NIL) { "enqueueIfAbsent requires a caller-assigned persistent job ID" }
        if (job.message != null) error("Cannot enqueue job with message: ${job.message}")
        job.setStatus(JobStatus.PENDING, true)
        job.queueName = name
        val serializedJob = job.serialize()
        dbRunAfterCommit { enqueueIfAbsent(serializedJob) }
        return serializedJob.id
    }

    private suspend fun enqueueIfAbsent(serializedJob: SerializedJob) = withContext(JobsDispatcher) {
        val jobId = serializedJob.id.toString()
        val state = kv()
        val unpublished = serializedJob.copy(status = JobStatus.UNINITIALIZED)
        val encoded = json.encodeToString(SerializedJob.serializer(), unpublished).toByteArray()
        val revision = try {
            state.create(jobId, encoded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val existing = try {
                state.get(jobId)
            } catch (readFailure: CancellationException) {
                throw readFailure
            } catch (readFailure: Exception) {
                e.addSuppressed(readFailure)
                throw e
            }
            if (existing == null) throw e
            return@withContext
        }

        publish(unpublished, revision)
        log.debug(
            "Enqueued persistent job if absent: {} : {} -> {}",
            jobId,
            serializedJob.executor,
            serializedJob.definition,
        )
    }

    private suspend fun enqueue(job: Job, serializedJob: SerializedJob) = withContext(JobsDispatcher) {
        val unpublished = serializedJob.copy(status = JobStatus.UNINITIALIZED)
        val revision = kv().put(job.id.toString(), json.encodeToString(SerializedJob.serializer(), unpublished))
        publish(unpublished, revision)
        log.debug("Enqueued job: {} : {} : {} -> {}", job.id, job.executor, job.executorName, serializedJob.definition)
    }

    override suspend fun enqueueLater(job: Job, timeout: Duration): UUID {
        if (!timeout.isPositive()) {
            return enqueue(job)
        }
        val message = job.message as? Message
        job.setStatus(JobStatus.PENDING, true)
        job.queueName = name
        val serializedJob = job.serialize()
        dbRunAfterCommit {
            enqueueLater(job, serializedJob, timeout, message)
            job.message = null
        }
        return serializedJob.id
    }

    private suspend fun enqueueLater(job: Job, serializedJob: SerializedJob, timeout: Duration, message: Message?) = withContext(JobsDispatcher) {
        val jobId = job.id.toString()
        val scheduledAt = System.currentTimeMillis() + timeout.inWholeMilliseconds
        if (message != null) {
            kv().put(jobId, json.encodeToString(SerializedJob.serializer(), serializedJob))
            scheduledKv().put(jobId, scheduledAt.toString())
            message.nakWithDelay(java.time.Duration.ofMillis(timeout.inWholeMilliseconds.coerceAtLeast(1)))
            log.debug("Delayed job {} for {}", jobId, timeout)
        } else {
            // The schedule must exist before an unpublished job is visible to recovery.
            scheduledKv().put(jobId, scheduledAt.toString())
            val unpublished = serializedJob.copy(status = JobStatus.UNINITIALIZED)
            val revision = kv().put(jobId, json.encodeToString(SerializedJob.serializer(), unpublished))
            publish(unpublished, revision)
            log.info("Enqueued job later: {} :: {}", jobId, timeout)
        }
    }

    private suspend fun publish(job: SerializedJob, revision: Long) {
        val id = job.id.toString()
        // Reuse the KV revision until publication is confirmed. A later explicit enqueue
        // gets a new revision and therefore a distinct JetStream deduplication key.
        js().publish(subject, id.toByteArray(), PublishOptions.builder().messageId("$id-$revision").build())
        try {
            kv().update(id, json.encodeToString(SerializedJob.serializer(), job.copy(status = JobStatus.PENDING)), revision)
        } catch (e: JetStreamApiException) {
            // A consumer can advance or complete the job before publish() returns.
            if (e.apiErrorCode != WRONG_LAST_SEQUENCE) throw e
        }
    }

    private suspend fun dbRunAfterCommit(block: suspend () -> Unit) {
        val dbConnection = connectionOrNull()
        if (dbConnection != null && dbConnection.inTransaction) {
            dbConnection.addCallback(object : ConnectionManagerCallback {
                override suspend fun onCommit() {
                    block()
                }

                override suspend fun onRelease() {
                }
            })
        } else {
            block()
        }
    }

    override suspend fun dequeue(): Job? = dequeue(30.seconds)

    override suspend fun dequeue(waitTimeout: Duration): Job? {
        require(waitTimeout.isPositive()) { "waitTimeout must be positive" }
        // Drain within a single call. A message that turns out to be orphaned (its
        // state is already gone), scheduled for later, or locked by another worker is
        // acked/nak'd and we immediately pull the next one instead of returning.
        //
        // Returning null after discarding such a message makes the caller (JobRunner's
        // fetcher) treat the queue as empty and apply its exponential idle backoff
        // (capped at ~1s). When the stream is full of re-queue duplicates that throttles
        // real throughput to roughly one message per second and starves live jobs for
        // hours — the "not found in state, acking" stall seen in production. We only
        // return null when a fetch genuinely comes back empty.
        var fetchWait = java.time.Duration.ofMillis(waitTimeout.inWholeMilliseconds.coerceAtLeast(1))
        while (true) {
            val msgs = try {
                sub().fetch(1, fetchWait)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                if (e.message == "This subscription is inactive") {
                    // TODO: re-establish the subscription
                    log.error("Subscription is inactive, retrying")
                }
                emptyList<Message>()
            } catch (e: Exception) {
                log.error("Failed to dequeue", e)
                emptyList<Message>()
            }

            if (msgs.isEmpty()) {
                return null
            }
            // We pulled a message; any further pulls in this same call are draining a
            // backlog, not idling, so don't block for the full idle window again.
            fetchWait = java.time.Duration.ofMillis(200)

            val msg = msgs.firstOrNull() ?: return null
            val jobIdString = msg.data.toString(Charsets.UTF_8)
            val jobId = try {
                UUID.parse(jobIdString)
            } catch (e: Exception) {
                log.error("Failed to parse job id from message, acking", e)
                msg.ack()
                continue
            }

            val scheduledEntry = try {
                scheduledKv().get(jobId.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (scheduledEntry != null && scheduledEntry.operation != KeyValueOperation.DELETE && scheduledEntry.operation != KeyValueOperation.PURGE) {
                val scheduledAtString = scheduledEntry.valueAsString
                if (scheduledAtString != null) {
                    val scheduledAt = scheduledAtString.toLong()
                    val now = System.currentTimeMillis()
                    if (scheduledAt > now) {
                        msg.nakWithDelay(java.time.Duration.ofMillis(scheduledAt - now))
                        continue
                    }
                }
                scheduledKv().purge(jobId.toString(), TERMINAL_PURGE_TTL)
            }

            // Check if the job state still exists before trying to lock/dequeue
            val entry = try {
                kv().get(jobId.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to read job state for $jobId, retrying message", e)
                msg.nakWithDelay(java.time.Duration.ofSeconds(5))
                continue
            }
            val entryValue = entry?.valueAsString
            if (entryValue == null || entry.operation == KeyValueOperation.DELETE || entry.operation == KeyValueOperation.PURGE) {
                log.warn("Job $jobId not found in state (possibly cancelled), acking message")
                msg.ack()
                continue
            }

            try {
                val dequeued = internalGetJob(jobId, 120_000, releaseLock = false, wait = false) {
                    // A completed execution may still retain state while its children run.
                    if (it?.status == JobStatus.COMPLETE || it?.status == JobStatus.FAILED_AND_COMPLETE) {
                        msg.ack()
                        return@internalGetJob null
                    }
                    it?.message = msg
                    if (it != null) {
                        it.setStatus(JobStatus.RUNNING)
                        kv().put(it.id.toString(), json.encodeToString(SerializedJob.serializer(), it.serialize()))
                    }
                    it
                }
                if (dequeued != null) return dequeued
            } catch (e: CancellationException) {
                throw e
            } catch (e: LockAcquisitionException) {
                log.warn("Failed to acquire lock for job $jobId, nak-ing message", e)
                msg.nakWithDelay(java.time.Duration.ofSeconds(30))
                continue
            } catch (e: Exception) {
                log.warn("Failed to acquire lock for job $jobId, nak-ing message", e)
                msg.nakWithDelay(java.time.Duration.ofSeconds(5))
                continue
            }
        }
    }

    override suspend fun clearJobLock(id: UUID) {
        distributedLockFactory.forceRelease("job-$id")
    }

    override suspend fun clearAllJobLocks(): Unit = withContext(Dispatchers.IO) {
        val keys = try {
            kv().keys()
        } catch (e: Exception) {
            log.warn("Failed to list keys for clearing job locks in queue {}: {}", queue, e.message)
            return@withContext
        }
        for (key in keys) {
            distributedLockFactory.forceRelease("job-$key")
        }
    }

    companion object {
        private const val WRONG_LAST_SEQUENCE = 10071
        // Keep removal notifications across several 30-second reconciliation passes while
        // bounding terminal metadata to about ten minutes (purge TTL plus limit-marker TTL).
        private const val KEY_VALUE_MARKER_TTL_SECONDS = 5 * 60
        private val KEY_VALUE_MARKER_TTL = java.time.Duration.ofSeconds(KEY_VALUE_MARKER_TTL_SECONDS.toLong())
        private val TERMINAL_PURGE_TTL = MessageTtl.seconds(KEY_VALUE_MARKER_TTL_SECONDS)
        private val log = LoggerFactory.getLogger(NatsJobQueue::class.java)
    }
}
