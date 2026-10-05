package bosca.sharedqueue.jobs

import bosca.di.provide
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

abstract class AbstractJobExecutor<T>(private val jobSerializer: KSerializer<T>) : JobExecutor {

    protected val Job.internalDefinition: JsonElement
        get() = definition

    protected suspend fun getJobDefinition(): T {
        val job = job()
        return provide<Json>().decodeFromJsonElement<T>(jobSerializer, job.internalDefinition)
    }

    /** Queue context attached by infrastructure such as the cron scheduler. */
    protected suspend fun getJobContext(): JsonElement = job().getContext()

    protected suspend fun setJobDefinition(attributes: T) {
        val attrs = provide<Json>().encodeToJsonElement(jobSerializer, attributes)
        val queue = jobQueue()
        val job = job()
        queue.setDefinition(job, attrs)
    }

    protected suspend fun setContext(context: JsonObject) {
        val queue = jobQueue()
        val job = job()
        job.setContext(context)
        queue.setJob(job)
    }
}
