package bosca.sharedqueue.jobs

import java.util.concurrent.ConcurrentHashMap

/**
 * The live [JobQueue] instances of this process, keyed by physical queue name ([JobQueue.name]).
 *
 * Exists for cross-queue job relationships: a child job may run on a different queue than the parent
 * that awaits it (e.g. a workops backing job parked under a pipelines run job). When that child
 * completes, [bosca.sharedqueue.jobs.listeners.NotifyParentListener] must look the parent up in the
 * PARENT's queue — resolvable here by the [Job.parentQueue] name the child carries. DI can't serve
 * this lookup: provider names don't always match physical queue names, and [JobQueueFactory.create]
 * builds a fresh instance (with its own expired-jobs sweeper) per call.
 *
 * Both factories register every queue they create; last registration for a name wins (in practice each
 * name is created once per process, as a singleton provider).
 */
object JobQueueRegistry {

    private val queues = ConcurrentHashMap<String, JobQueue>()

    fun register(queue: JobQueue) {
        queues[queue.name] = queue
    }

    /** The live queue named [name], or null when this process hasn't created it. */
    fun find(name: String): JobQueue? = queues[name]

    /** Test isolation only — a process never unregisters queues. */
    internal fun clear() {
        queues.clear()
    }
}
