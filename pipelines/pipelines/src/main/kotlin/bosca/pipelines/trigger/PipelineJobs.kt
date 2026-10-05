@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.trigger

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * One platform event occurrence, queued for triggered-pipeline matching on the runner. The
 * event-firing process only does a cached gate + this single enqueue; matching fans out into one
 * [PipelineRunJob] per triggered pipeline.
 *
 * [eventCreated] is stamped when the event fires (the queue only adds delivery latency), so
 * pipelines know when the occurrence actually happened. The decode-time default only covers
 * payloads enqueued before the field existed.
 */
@Serializable
data class PipelineDispatchJob(
    val eventName: String,
    val eventPayload: JsonElement,
    @Contextual
    val eventCreated: OffsetDateTime = OffsetDateTime.now(),
) : IJobDefinition

/** One triggered pipeline execution for one event occurrence ([eventCreated] = event fire time). */
@Serializable
data class PipelineRunJob(
    @Contextual
    val pipelineId: UUID,
    val eventName: String,
    val eventPayload: JsonElement,
    @Contextual
    val eventCreated: OffsetDateTime = OffsetDateTime.now(),
) : IJobDefinition

/**
 * A durable timer expressed as a **child job of the run**. Its executor re-queues
 * itself via `DelayException` until [wakeAtEpochMillis], then completes; that completion bubbles up to
 * the run job, whose drive listener resumes the parked timer node. The run/node correlation rides in
 * the job's `context` (a [bosca.pipelines.node.PipelineResumeCorrelation]), not the definition.
 */
@Serializable
data class PipelineDelayJob(
    val wakeAtEpochMillis: Long,
) : IJobDefinition

/**
 * Drives an already-created **child run** (a RunPipeline/ForEach child, [childRunId]) as a child job
 * of the parent run job. Driving it in its own job — rather than inline in
 * the parent's suspend thunk — keeps the parent run job open while the child is in flight and lets the
 * child itself suspend durably (its own backing/delay children). The child reports its output back to
 * the parent the usual way (the run service's iteration reporting) on completion.
 */
@Serializable
data class PipelineChildRunJob(
    @Contextual
    val childRunId: UUID,
) : IJobDefinition

/**
 * Drives an already-created **on-demand run** (a manual / API run, [runId]) as its own run job.
 * An on-demand run is initiated from a request thread that is *not* itself a run
 * job, so — unlike a triggered/scheduled run — there is no job for a suspending node to attach its
 * backing work to. The caller creates the run row and enqueues this job; its executor carries the run
 * job's drive listener and drives the run, so a node that suspends parks durably and resumes the same
 * way a triggered run does (rather than orphaning in SUSPENDED with a parentless backing job).
 */
@Serializable
data class PipelineManualRunJob(
    @Contextual
    val runId: UUID,
) : IJobDefinition

/** Cron-scheduled backstop: fails runs stuck suspended past the configured max lifetime. */
@Serializable
class PipelineSuspendedSweepJob : IJobDefinition

/** Cron-scheduled retention sweep: reaps terminal run-state + old run-history past their windows. */
@Serializable
class PipelineRetentionSweepJob : IJobDefinition

/**
 * One scheduled (cron-fired) pipeline execution. The SchedulerService dispatches
 * this with the target [pipelineId] from the pipeline's schedule; the executor starts a durable run.
 */
@Serializable
data class PipelineScheduledRunJob(
    @Contextual
    val pipelineId: UUID,
) : IJobDefinition
