package bosca.sharedqueue.jobs

interface JobExecutor {

    suspend fun getLockId(): String? = null

    /**
     * Opt-in flag that changes what happens when the runner dequeues a job
     * whose executor-level distributed lock (keyed by [getLockId]) is
     * already held by a sibling executor running elsewhere in the fleet.
     *
     * - When `false` (the default), the runner throws a short
     *   [DelayException] and requeues the job, so it will retry shortly and
     *   run as soon as the sibling finishes. This is the correct behaviour
     *   for executors whose work is distinct between siblings and must
     *   eventually run.
     * - When `true`, the runner silently marks the job complete without
     *   calling [execute]. This is the correct behaviour for executors that
     *   drive a per-entity state machine where a sibling currently running
     *   for the same entity already subsumes the new work — for example,
     *   a second Mux upload job for the same metadata is redundant while
     *   the first one holds the lock, and requeuing it would just pile up
     *   duplicate no-op work behind the in-flight executor.
     *
     * Implementations that set this to `true` **must** also implement
     * [getLockId] to return a non-null, entity-stable value — otherwise
     * the flag has no effect because no lock is acquired in the first
     * place.
     *
     * The decision is made under the runner's own lock acquisition, so
     * this avoids the extra backend round trip that an enqueue-time probe
     * would require. The trade-off is that the redundant job still lands
     * in the queue and occupies a dequeue slot before being collapsed,
     * which is cheaper than a network probe per enqueue.
     */
    val skipExecutionIfLocked: Boolean get() = false

    suspend fun execute()
}

interface JobListener {

    suspend fun onStatusChanged(job: Job, status: JobStatus, errorMessage: String? = null) {}

    /**
     * Fired on a **parent's** listeners when one of its immediate children reaches a terminal status,
     * with the parent already loaded and **locked** ([job] is the parent, [child] the child that
     * changed). This is the hook a coordinator uses to monitor its immediate dependents and react —
     * e.g. drive itself forward and enqueue the next child. Because the parent is held locked when this
     * runs, an implementation may [Job.addChild]/enqueue directly onto [job]; it runs before the
     * parent's fully-complete is re-evaluated, so a child added here keeps the parent open.
     */
    suspend fun onChildStatusChanged(job: Job, child: Job, status: JobStatus) {}

    /**
     * As [onChildStatusChanged], with the child's terminal [errorMessage] (null on success). Default
     * delegates to the 3-arg form so existing listeners keep working; override this one to surface the
     * REAL failure instead of a generic "child failed".
     */
    suspend fun onChildStatusChanged(job: Job, child: Job, status: JobStatus, errorMessage: String?) =
        onChildStatusChanged(job, child, status)
}