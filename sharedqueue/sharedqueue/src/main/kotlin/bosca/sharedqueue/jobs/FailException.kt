package bosca.sharedqueue.jobs

/**
 * Permanently fails a job.
 */
class FailException(message: String) : Exception(message)