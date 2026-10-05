package bosca.pipelines.model

import kotlinx.serialization.Serializable

/**
 * One row of a run's step-oriented progress view: a Status node the author placed
 * as a milestone, or a HUMAN wait (Approval Gate / Wait for Input). Fan-out children contribute rows
 * per item; [depth]/[item] place a row within its parent's flow.
 */
@Serializable
data class RunStep(
    /** The node this row represents, within its own pipeline's graph. */
    val nodeId: String,
    val title: String,
    val kind: RunStepKind,
    val status: RunStepStatus,
    /** Child-pipeline nesting depth; 0 = the run's own graph. */
    val depth: Int = 0,
    /** For a fan-out child run's rows: which item (e.g. "my-api"), null otherwise. */
    val item: String? = null,
    /** The run this row's node belongs to — for a nested row, the CHILD run's id (what an Approve
     *  control must resolve against). Null on pre-run plan rows (no run exists yet). */
    val runId: bosca.serialization.UUID? = null,
    /** The node-type key (`status`, `gate.approval`, `waitForInput`) — which controls a WAITING row offers. */
    val type: String? = null,
    /**
     * The artifact type of the channel this row belongs to (the `artifactType` of the single upstream
     * `artifact.select` feeding it), or null for channel-agnostic rows. A UI showing a pre-run plan can
     * drop channel rows the release's projects declare no artifact for.
     */
    val channelType: String? = null,
)

/**
 * A node a run is ACTUALLY parked on, drilled through fan-outs: when the parent parks on a For Each /
 * Run Pipeline, the rows here are the child runs' own parked nodes (labelled with their item), not the
 * container — "waiting on Wait for build (item 2)", never "waiting on Each project version".
 */
@Serializable
data class RunAwaitingNode(
    val nodeId: String,
    /** The node-type key — `gate.approval` / `waitForInput` mean a human is being waited on. */
    val type: String,
    /** Display name (falls back through name/title/prompt to the id), with the fan-out item appended. */
    val name: String,
    /** The run actually parked on this node — for a drilled-through row, the CHILD run's id (what a
     *  resolveGate/provideInput must target). */
    val runId: bosca.serialization.UUID? = null,
)

@Serializable
enum class RunStepKind {
    /** An authored Status milestone. */
    STATUS,

    /** A human wait: Approval Gate or Wait for Input. */
    HUMAN,
}

@Serializable
enum class RunStepStatus {
    /** Not reached yet. */
    PENDING,

    /** The run is between the previous milestone and this one. */
    RUNNING,

    /** A human wait the run is currently parked on. */
    WAITING,

    /** Passed. */
    DONE,

    /** The run failed (or was cancelled) before passing this row. */
    FAILED,
}
