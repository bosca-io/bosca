package bosca.sharedqueue.jobs

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.listeners.NotifyParentListener
import bosca.sharedqueue.jobs.listeners.RunChildOnCompleteListener
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.reflect.KClass

@Serializable
enum class JobStatus {
    UNINITIALIZED,
    PENDING,
    RUNNING,
    COMPLETE,

    /** A failure that will be retried (the job's failure count is still below its limit). NON-terminal. */
    FAILED,

    /**
     * The terminal failure: the job exhausted its retries (or failed non-retryably). It counts as
     * "complete" for a parent's [Job.isFullyComplete] — a terminally-failed child no longer blocks its
     * parent — and a [Job.runOnFailure] child of such a job is still run, carrying the failure forward.
     */
    FAILED_AND_COMPLETE
}

class JobCallback(
    internal val listener: KClass<out JobListener>,
    internal val listenerName: String? = null
) {

    @OptIn(InternalDI::class)
    suspend fun newListener(): JobListener {
        return listenerName?.let { ProviderRegistry.get(listener, it).get() } ?: ProviderRegistry.get(listener).get()
    }
}

@OptIn(Internal::class)
suspend inline fun <reified T : IJobDefinition> Job(
    definition: T,
    executor: KClass<out JobExecutor>,
    executorName: String? = null,
    displayName: String? = null,
) = InternalJobConstructor(
    definition = provide<Json>().encodeToJsonElement<T>(definition),
    executor = executor,
    executorName = executorName,
    displayName = displayName,
)

@Suppress("FunctionName")
@Internal
fun InternalJobConstructor(
    definition: JsonElement,
    executor: KClass<out JobExecutor>,
    executorName: String? = null,
    displayName: String? = null,
) = Job(
    definition = definition,
    executor = executor,
    executorName = executorName,
    displayName = displayName,
)

class Job
internal constructor(
    @JvmField internal var parentId: UUID? = null,
    @JvmField
    internal var id: UUID = UUID.NIL,
    internal var definition: JsonElement,
    internal var status: JobStatus = JobStatus.UNINITIALIZED,
    @JvmField internal var children: List<Job> = emptyList(),
    @Contextual
    internal val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    internal var modified: OffsetDateTime = OffsetDateTime.now(),
    internal val executor: KClass<out JobExecutor>,
    /**
     * Optional DI lookup key used by [newExecutor] to disambiguate multiple
     * providers of [executor]. When non-null, the runner resolves the executor
     * via `ProviderRegistry.get(executor, executorName)` instead of the default
     * unnamed lookup — so setting this to a value that does NOT correspond to a
     * registered named provider WILL cause dequeue to fail with
     * [bosca.di.MissingProviderException]. This is **not** a UI label; use
     * [displayName] for that.
     */
    internal val executorName: String? = null,
    /**
     * Optional human-readable label propagated into enqueue events and, from
     * there, into admin job-history rows. Purely cosmetic — never used as a DI
     * lookup key, so it is safe to populate from `@JobDefinition(displayName = ...)`
     * without affecting runtime resolution.
     */
    internal val displayName: String? = null,
    internal var failures: Int = 0,
    internal val maxFailures: Int = 10,
    /**
     * When `true`, this job is still run by its parent's [RunChildOnCompleteListener] when the parent
     * reaches [JobStatus.FAILED_AND_COMPLETE] — not only when the parent completes successfully. Used to
     * carry a failure forward (e.g. a pipeline resume that must run whether its backing job succeeded or
     * terminally failed). Defaulted `false` for backward-compatible deserialization.
     */
    internal var runOnFailure: Boolean = false,
    internal val callbacks: MutableList<JobCallback> = mutableListOf(),
    @JvmField internal var context: JsonElement = JsonNull,
    /**
     * The physical name of the queue this job was enqueued on ([JobQueue.name]), stamped by the queue
     * at enqueue time and persisted. Null until first enqueued (or for states written before the field
     * existed).
     */
    internal var queueName: String? = null,
    /**
     * The physical name of the queue the PARENT job lives on, stamped by [addChild] from the parent's
     * [queueName]. A completing child's `NotifyParentListener` resolves the parent's queue by this name
     * — without it, a cross-queue child would look its parent up in the child's own queue, silently
     * find nothing, and the parent would never learn the child finished.
     */
    internal var parentQueue: String? = null,
) {

    @Transient
    internal var message: Any? = null
    @Transient
    internal var lock: DistributedLock? = null
    /** Suppresses publication of the enqueue event for this job. */
    @Transient
    var disableEmitEvent: Boolean = false
    /**
     * Suppresses only the callbacks configured on an event-emitting queue at enqueue time.
     *
     * Explicit callbacks already attached to the job are preserved. This is intended for queue
     * records consumed by a process that does not register the producer's default listeners.
     */
    @Transient
    var disableEnqueueCallbacks: Boolean = false

    val isLocked: Boolean get() = id == UUID.NIL || lock?.isHeld == true

    fun getId() = id

    internal fun setId(id: UUID) {
        if (this.id == id) return
        if (!isLocked) throw IllegalStateException("Cannot set id $id, it is not locked")
        this.id = id
        children.forEach { it.setParent(id) }
    }

    /**
     * Assigns an identifier persisted by an external durable outbox before queue publication.
     *
     * The job must still be unqueued/locked, and the identifier must not be NIL.
     */
    fun setPersistentId(id: UUID) {
        require(id != UUID.NIL) { "A persistent job identifier must not be NIL" }
        setId(id)
    }

    fun addCallback(callback: JobCallback) {
        addCallback(callback, false)
    }

    internal fun addCallback(callback: JobCallback, ignoreLock: Boolean) {
        if (callbacks.any { it.listener == callback.listener && it.listenerName == callback.listenerName }) return
        if (!ignoreLock && !isLocked) throw IllegalStateException("Cannot add callback to $id, it is not locked")
        callbacks += callback
        modified = OffsetDateTime.now()
    }

    fun getParentId() = parentId

    fun setParent(id: UUID) {
        if (!isLocked) throw IllegalStateException("Cannot set parent $id, it is not locked")
        if (parentId == id) return
        parentId = id
        addCallback(JobCallback(listener = NotifyParentListener::class))
    }

    fun getChildren() = children

    /**
     * Attach [child] to this job for the fully-complete join — the parent is not [isFullyComplete]
     * until every child is.
     *
     * By default ([runOnParentComplete] = true) the parent also gets a [RunChildOnCompleteListener], so
     * when the parent reaches a terminal status its children are enqueued — the fan-out / run-after-parent
     * pattern (e.g. MultiJob). Pass `false` for a child that is launched some other way (e.g. a
     * coordinator that enqueues it itself as it drives forward) and so must NOT be re-run when the parent
     * completes; it is still tracked for the join and still notifies the parent on completion.
     */
    fun addChild(child: Job, runOnParentComplete: Boolean = true) {
        if (!isLocked) throw IllegalStateException("Cannot add child to $id, it is not locked")
        if (id != UUID.NIL) child.setParent(id)
        // The child may be enqueued on a DIFFERENT queue than this parent — carry the parent's queue so
        // the child's completion can find this job (queueName is null only if this parent was never
        // enqueued, in which case the child completes on the same queue and the same-queue fallback holds).
        child.parentQueue = queueName
        children += child
        if (runOnParentComplete && !callbacks.any { it.listener == RunChildOnCompleteListener::class }) {
            addCallback(JobCallback(listener = RunChildOnCompleteListener::class))
        }
    }

    fun setStatus(status: JobStatus) {
        setStatus(status, false)
    }

    internal fun setStatus(status: JobStatus, ignoreLock: Boolean) {
        if (this.status == status) return
        if (!ignoreLock && !isLocked) throw IllegalStateException("Cannot set status ($status) of job $id, it is not locked")
        this.status = status
        modified = OffsetDateTime.now()
    }

    fun getRunOnFailure() = runOnFailure

    /** Mark this job to run even when its parent terminally fails (see [runOnFailure]). */
    fun setRunOnFailure(value: Boolean) {
        if (!isLocked) throw IllegalStateException("Cannot set runOnFailure of job $id, it is not locked")
        runOnFailure = value
        modified = OffsetDateTime.now()
    }

    fun isFullyComplete(): Boolean {
        // A terminal failure (FAILED_AND_COMPLETE) is "done" — it counts as complete so it doesn't
        // hang the parent; a non-terminal FAILED (still retrying) does not.
        if (status != JobStatus.COMPLETE && status != JobStatus.FAILED_AND_COMPLETE) return false
        return areChildrenComplete()
    }

    fun areChildrenComplete(): Boolean {
        return children.all { it.isFullyComplete() }
    }

    fun setChildStatus(id: UUID, status: JobStatus) {
        if (!isLocked) throw IllegalStateException("Cannot set child status ($status) of job $id, it is not locked")
        val child = children.find { it.id == id } ?: error("Child with id $id not found")
        child.setStatus(status, true)
        child.setContext(context, true)
    }

    fun getContext() = context

    fun getDefinition() = definition

    fun setContext(context: JsonElement) {
        setContext(context, false)
    }

    inline fun <reified T> setContext(json: Json, context: T) = setContext(json.encodeToJsonElement<T>(context))

    private fun setContext(context: JsonElement, ignoreLock: Boolean) {
        if (!ignoreLock && !isLocked) throw IllegalStateException("Cannot set status of job $id, it is not locked")
        this.context = context
    }

    @OptIn(InternalDI::class)
    suspend fun newExecutor(): JobExecutor {
        return executorName?.let { ProviderRegistry.get(executor, it).get() } ?: ProviderRegistry.get(executor).get()
    }
}

@Internal
suspend fun newJobLock(distributedLockFactory: DistributedLockFactory, id: UUID, lockTimeout: Long, wait: Boolean): DistributedLock {
    val lock = distributedLockFactory.create("job-$id")
    if (!wait) {
        if (!lock.tryAcquire(lockTimeout)) {
            throw LockAcquisitionException("Failed to acquire lock for job $id")
        }
    } else {
        if (!lock.acquire(lockTimeout, 5_000)) {
            throw LockAcquisitionException("Failed to acquire lock for job $id")
        }
    }
    return lock
}

class LockAcquisitionException(message: String) : Exception(message)
