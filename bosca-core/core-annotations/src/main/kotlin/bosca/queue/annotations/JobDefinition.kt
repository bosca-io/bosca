package bosca.queue.annotations

import kotlin.reflect.KClass

/**
 * Associates a job executor class with its payload type, target queue, and identifying names.
 *
 * Applied to classes that implement a job executor; the KSP symbol processor reads this
 * annotation to generate the per-job provider, enqueue extensions, and
 * [JobConfigurationEnqueuer] plumbing.
 *
 * @property definition the payload class this executor consumes; must implement [IJobDefinition]
 *   and be referenced so the generated enqueuer can round-trip the configuration through JSON
 * @property queue the name of the shared queue (as registered with the [bosca.sharedqueue.jobs.JobQueueFactory])
 *   that jobs of this definition will be dispatched to; consumers of that queue pick up and run them
 * @property name the stable identifier used to look up this job's [JobConfigurationEnqueuer] via DI
 *   (`provide<JobConfigurationEnqueuer>(name = "...")`) and to register it in the job catalog; must be
 *   unique within the process and is the value that appears in wire/persisted references to the job
 * @property displayName a human-friendly label used by admin UIs and log lines; blank (default) falls
 *   back to [name]. The generated enqueue helpers propagate this value as
 *   [bosca.sharedqueue.jobs.Job.executorName] so it flows through enqueue events and into job history
 *   rows — giving operators recognizable rows like "Metadata Transition" instead of fully-qualified
 *   class names.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class JobDefinition(
    val definition: KClass<out IJobDefinition>,
    val queue: String,
    val name: String,
    val displayName: String = ""
)
