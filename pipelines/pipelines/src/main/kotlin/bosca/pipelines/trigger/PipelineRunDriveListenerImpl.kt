package bosca.pipelines.trigger

import bosca.di.provide
import bosca.pipelines.node.PipelineResumeCorrelation
import bosca.pipelines.node.PipelineRunDriveListener
import bosca.pipelines.service.PipelineRunService
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The run job's drive hook ("run = a job"). The run job carries this listener; when one
 * of its immediate child jobs — a backing job a suspendable node enqueued and parked the run on —
 * reaches a terminal status, [onChildStatusChanged] resumes the run from that node, driving the next
 * segment and (if the run parks again) enqueuing the next backing job as the next child.
 *
 * The run job ([job]) is held **locked** when this fires (the bubble-up via `NotifyParentListener`
 * holds its lock), so the resume threads it through as the run job to attach the next child to directly
 * — and because this runs before the parent's fully-complete is re-evaluated, a freshly-attached child
 * keeps the run job open. The run job therefore becomes fully complete exactly when the run does.
 */
class PipelineRunDriveListenerImpl(
    private val json: Json,
) : PipelineRunDriveListener {

    override suspend fun onChildStatusChanged(job: Job, child: Job, status: JobStatus, errorMessage: String?) {
        // `job` is the run job (monitoring its immediate children); `child` is a backing job that just
        // reached a terminal status and carries the run/node correlation to resume from.
        val correlation = decodeCorrelation(child.getContext()) ?: return
        val succeeded = status == JobStatus.COMPLETE
        provide<PipelineRunService>().resume(
            correlation.runId,
            correlation.nodeId,
            succeeded,
            // The child's own terminal error is the story; the node-id phrasing is only the fallback.
            if (succeeded) null else (concise(errorMessage) ?: "backing work for node '${correlation.nodeId}' failed"),
            runJob = job,
        )
    }

    /**
     * The failed child's error reduced to its message: the queue records the FULL stack trace (right
     * for job history), but a run's error is read by a person — first line only, and a bare
     * `com.example.SomeException: ` prefix is noise there too.
     */
    private fun concise(errorMessage: String?): String? {
        val first = errorMessage?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val prefix = first.substringBefore(": ", "")
        val looksLikeExceptionClass = prefix.isNotEmpty() && ' ' !in prefix && '.' in prefix
        return if (looksLikeExceptionClass) first.substringAfter(": ") else first
    }

    /**
     * The child's context is a [PipelineResumeCorrelation] for a pipeline backing job; a child that is
     * not a pipeline backing job (no/foreign context) is ignored.
     */
    private fun decodeCorrelation(context: JsonElement): PipelineResumeCorrelation? =
        try {
            json.decodeFromJsonElement(PipelineResumeCorrelation.serializer(), context)
        } catch (e: Exception) {
            null
        }
}
