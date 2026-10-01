package bosca.mux.jobs

/**
 * Constants for Mux integration job-queue naming, following the same
 * pattern used by the content module's [bosca.content.configuration.JobQueueNames].
 */
object MuxJobQueueNames {
    /** DI provider name for the Mux [bosca.sharedqueue.jobs.JobQueue] instance. */
    const val muxJobQueue = "muxQueue"
    /** DI provider name for the Mux [bosca.sharedqueue.jobs.JobRunner] instance. */
    const val muxRunner = "muxQueueRunner"
    /** The underlying queue name used when creating the job queue via the factory. */
    const val muxQueue = "mux"
}
