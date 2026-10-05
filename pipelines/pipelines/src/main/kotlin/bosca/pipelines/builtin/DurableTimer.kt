package bosca.pipelines.builtin

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunResultStore
import bosca.pipelines.trigger.PipelineDelayJob
import bosca.pipelines.trigger.enqueue
import kotlinx.serialization.json.JsonNull
import kotlin.time.Duration

/**
 * Shared mechanism for **timer nodes** (Delay, WaitUntil): park a durable run for [delay] and resume
 * it with the [inbound] value passed through when the time elapses.
 *
 * Returns a [NodeResult.Suspend] whose deferred thunk — run only **after** the suspended checkpoint is
 * durably persisted — does two things:
 *  1. stages [inbound] in the [PipelineRunResultStore] (a timer is a transparent pass-through gate), so
 *     the resume replays it on the node's implicit output;
 *  2. enqueues a [PipelineDelayJob] as a **child of the run job** via the generated `enqueue(context,
 *     nodeId)` (which stamps the run/node correlation and attaches the child). That child re-queues
 *     itself via the platform's native `DelayException` until the wake time, then completes — and its
 *     completion bubbles up to the run job, whose drive listener resumes this node. Being a child keeps
 *     the run job open for the whole wait.
 *
 * The wait is durable end to end (a queue row + a Postgres checkpoint) and survives worker restarts.
 */
internal fun durableTimerSuspend(
    context: PipelineContext,
    nodeId: String,
    inbound: PipelineValue?,
    delay: Duration,
): NodeResult.Suspend = NodeResult.Suspend {
    val runId = context.runId ?: error("durable timer requires a run id")
    // Always stage (JSON null when no inbound value) so the resume always finds the staged output.
    provide<PipelineRunResultStore>().put(runId, nodeId, inbound?.encode(context.json) ?: JsonNull)
    PipelineDelayJob(wakeAtEpochMillis = System.currentTimeMillis() + delay.inWholeMilliseconds)
        .enqueue(context, nodeId)
}
