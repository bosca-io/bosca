package bosca.sharedqueue.jobs

interface JobQueueFactory {

    fun create(name: String): JobQueue
}