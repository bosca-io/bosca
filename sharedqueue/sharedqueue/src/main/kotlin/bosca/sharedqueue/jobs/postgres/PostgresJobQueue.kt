package bosca.sharedqueue.jobs.postgres

import bosca.cache.withRequestCache
import bosca.db.*
import bosca.lock.postgres.PostgresDistributedLock
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Persisted delivery state with short SQL claims and fenced per-job execution leases. */
internal class PostgresJobQueue(override val name: String, private val pool: ConnectionPool, private val json: Json) : JobQueue {
    override suspend fun enqueue(job: Job): UUID = enqueue(job, Duration.ZERO, false)
    override suspend fun enqueueLater(job: Job, timeout: Duration): UUID = enqueue(job, timeout, false)
    override suspend fun enqueueIfAbsent(job: Job): UUID {
        require(job.id != UUID.NIL) { "enqueueIfAbsent requires a persistent ID" }
        return enqueue(job, Duration.ZERO, true)
    }
    private suspend fun enqueue(job: Job, timeout: Duration, absent: Boolean): UUID {
        job.queueName = name
        job.setStatus(JobStatus.PENDING, true)
        val serialized = job.serialize()
        val payload = json.encodeToString(SerializedJob.serializer(), serialized)
        suspend fun insert(m: ConnectionManager) {
            m.useStatement("""
                insert into sharedqueue.jobs(queue, id, payload, available) values (?, ?::uuid, ?, clock_timestamp() + (? * interval '1 millisecond'))
                on conflict (queue, id) ${if (absent) "do nothing" else "do update set payload = excluded.payload, available = excluded.available, claimed_until = null"}
            """.trimIndent()) { stmt ->
                stmt.setString(1, name); stmt.setString(2, serialized.id.toString()); stmt.setString(3, payload)
                stmt.setLong(4, timeout.inWholeMilliseconds.coerceAtLeast(0)); stmt.executeUpdate()
            }
        }
        val manager = connectionOrNull()
        if (manager?.inTransaction == true) insert(manager) else pool.connection().use { insert(it) }
        return serialized.id
    }

    /** Next time this instance reclaims expired claims; claims last minutes, so polls need not each scan. */
    @Volatile private var nextExpiryCheck: TimeMark = TimeSource.Monotonic.markNow()

    override suspend fun dequeue(): Job? {
        if (nextExpiryCheck.hasPassedNow()) {
            nextExpiryCheck = TimeSource.Monotonic.markNow() + EXPIRY_CHECK_INTERVAL
            checkForExpiredJobs()
        }
        val claimed = pool.connection().use { m -> m.useStatement("""
            with candidate as (select id from sharedqueue.jobs where queue = ? and available <= clock_timestamp()
                order by available, id for update skip locked limit 1)
            update sharedqueue.jobs j set available = null, claimed_until = clock_timestamp() + interval '2 minutes'
            from candidate c where j.queue = ? and j.id = c.id returning j.payload, j.claimed_until
        """.trimIndent()) { stmt ->
            stmt.setString(1, name); stmt.setString(2, name)
            stmt.executeQuery().use { if (it.next()) it.getString(1) to it.getObject(2, OffsetDateTime::class.java) else null }
        } } ?: return null
        val job = json.decodeFromString(SerializedJob.serializer(), claimed.first).deserialize()
        val lock = PostgresDistributedLock(pool, "job:lock:${job.id}")
        if (!lock.tryAcquire(LEASE_TTL_MILLIS)) {
            // The previous owner still holds the lease, e.g. between a delayed re-enqueue and its lock
            // release. Return the claim so the job is retried shortly instead of after the claim expires.
            releaseClaim(job.id, claimed.second)
            return null
        }
        try {
            job.lock = lock
            job.setStatus(JobStatus.RUNNING)
            setJob(job)
            return job
        } catch (e: Exception) {
            withContext(NonCancellable) { lock.release() }
            throw e
        }
    }
    /** Makes a claim this worker could not lease available again, unless another worker has changed it since. */
    private suspend fun releaseClaim(id: UUID, claimedUntil: OffsetDateTime) {
        pool.connection().use { m -> m.useStatement("""
            update sharedqueue.jobs set available = clock_timestamp() + (? * interval '1 millisecond'), claimed_until = null
            where queue = ? and id = ?::uuid and available is null and claimed_until = ?
        """.trimIndent()) { stmt ->
            stmt.setLong(1, LEASE_CONTENTION_RETRY.inWholeMilliseconds); stmt.setString(2, name); stmt.setString(3, id.toString())
            stmt.setObject(4, claimedUntil); stmt.executeUpdate()
        } }
    }

    /**
     * Re-establishes this worker's lease before an outcome is recorded once its conservative local deadline has
     * passed, as the Redis queue re-acquires its job lock. The fenced update in [mutate] still rejects the outcome
     * when another worker has taken the lease.
     */
    private suspend fun reclaimLease(job: Job) {
        val lock = job.lock as? PostgresDistributedLock ?: return
        if (lock.isHeld) return
        log.warn("Job {} lease deadline passed, trying to reclaim it", job.id)
        if (!lock.renew(LEASE_TTL_MILLIS)) lock.tryAcquire(LEASE_TTL_MILLIS)
    }

    override suspend fun dequeue(waitTimeout: Duration): Job? {
        val start = TimeSource.Monotonic.markNow()
        do {
            dequeue()?.let { return it }
            val left = waitTimeout - start.elapsedNow()
            if (!left.isPositive()) return null
            delay(minOf(left, 100.milliseconds))
        } while (true)
    }

    override suspend fun <T> getJob(id: UUID, block: suspend (Job?) -> T): T {
        val lock = PostgresDistributedLock(pool, "job:lock:$id")
        check(lock.acquire(60_000, 60_000)) { "Failed to lock job $id" }
        try {
            val job = pool.connection().use { m -> m.useStatement("select payload from sharedqueue.jobs where queue = ? and id = ?::uuid") { stmt ->
                stmt.setString(1, name); stmt.setString(2, id.toString())
                stmt.executeQuery().use { if (it.next()) json.decodeFromString(SerializedJob.serializer(), it.getString(1)).deserialize() else null }
            } }
            job?.lock = lock
            return block(job)
        } finally { withContext(NonCancellable) { lock.release() } }
    }

    private suspend fun mutate(job: Job, assignments: String, delayMillis: Long? = null, delete: Boolean = false) {
        val lock = job.lock as? PostgresDistributedLock ?: error("Job ${job.id} has no PostgreSQL lease")
        check(lock.isHeld) { "Job ${job.id} lease expired" }
        val operation = if (delete) "delete from sharedqueue.jobs j" else "update sharedqueue.jobs j set payload = ?, $assignments"
        val changed = pool.connection().use { m -> m.useStatement("""
            $operation where j.queue = ? and j.id = ?::uuid and exists (
                select 1 from infrastructure.locks l where l.name = ? and l.owner = ? and l.expires > clock_timestamp() for update)
        """.trimIndent()) { stmt ->
            var i = 1
            if (!delete) stmt.setString(i++, json.encodeToString(SerializedJob.serializer(), job.serialize()))
            if (delayMillis != null) stmt.setLong(i++, delayMillis)
            stmt.setString(i++, name); stmt.setString(i++, job.id.toString()); stmt.setString(i++, "job:lock:${job.id}"); stmt.setObject(i, lock.owner)
            stmt.executeUpdate()
        } }
        check(changed == 1) { "Job ${job.id} was cancelled or lease ownership was lost" }
    }
    override suspend fun setJob(job: Job) { mutate(job, "claimed_until = claimed_until") }
    override suspend fun setDefinition(job: Job, definition: JsonElement) { job.definition = definition; setJob(job) }
    override suspend fun checkin(job: Job, lockRenew: Long): Boolean {
        if (job.lock?.renew(lockRenew) != true) return false
        mutate(job, "claimed_until = case when claimed_until is null then null else clock_timestamp() + (? * interval '1 millisecond') end", lockRenew)
        return true
    }
    override suspend fun markComplete(job: Job) {
        try {
            reclaimLease(job)
            job.setStatus(JobStatus.COMPLETE)
            mutate(job, "available = null, claimed_until = null", delete = job.isFullyComplete())
        } finally { withContext(NonCancellable) { job.lock?.release() } }
        notify(job, null)
    }
    override suspend fun markFailed(job: Job, exception: Exception, retry: Boolean) {
        try {
            reclaimLease(job)
            job.failures++
            val terminal = !retry || job.failures >= job.maxFailures
            job.setStatus(if (terminal) JobStatus.FAILED_AND_COMPLETE else JobStatus.FAILED)
            mutate(job, "available = clock_timestamp() + (? * interval '1 millisecond'), claimed_until = null", if (terminal) null else 30_000L * job.failures, delete = terminal)
        } finally { withContext(NonCancellable) { job.lock?.release() } }
        notify(job, exception.stackTraceToString())
    }
    private suspend fun notify(job: Job, failure: String?) {
        if (job.callbacks.isEmpty()) return
        withRequestCache { withConnectionManager { job.callbacks.forEach { it.newListener().onStatusChanged(job, job.status, failure) } } }
    }
    override suspend fun markCancelled(id: UUID) {
        pool.connection().use { m -> m.useStatement("delete from sharedqueue.jobs where queue = ? and id = ?::uuid") { stmt -> stmt.setString(1, name); stmt.setString(2, id.toString()); stmt.executeUpdate() } }
    }
    override suspend fun checkForExpiredJobs(time: Long) {
        pool.connection().use { m -> m.useStatement("update sharedqueue.jobs set available = clock_timestamp(), claimed_until = null where queue = ? and claimed_until <= to_timestamp(? / 1000.0)") { stmt -> stmt.setString(1, name); stmt.setLong(2, time); stmt.executeUpdate() } }
    }
    override suspend fun expireAllJobs() { checkForExpiredJobs(Long.MAX_VALUE / 1000) }
    override suspend fun clearJobLock(id: UUID) { bosca.lock.postgres.PostgresDistributedLockFactory(pool).forceRelease("job:lock:$id") }
    override suspend fun clearAllJobLocks() {
        val ids = pool.connection().use { m -> m.useStatement("select id::text from sharedqueue.jobs where queue = ?") { stmt ->
            stmt.setString(1, name); stmt.executeQuery().use { result -> buildList { while (result.next()) add(UUID.parse(result.getString(1))) } }
        } }
        ids.forEach { clearJobLock(it) }
    }

    private companion object {
        val EXPIRY_CHECK_INTERVAL = 5.seconds
        /** Delay before a claim released for lease contention becomes available again. */
        val LEASE_CONTENTION_RETRY = 1.seconds
        const val LEASE_TTL_MILLIS = 120_000L
        val log: Logger = LoggerFactory.getLogger(PostgresJobQueue::class.java)
    }
}
