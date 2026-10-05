package bosca.pipelines.model

import kotlinx.serialization.Serializable

/**
 * One outstanding await of a [PipelineRunStatus.SUSPENDED] run: the id of the parked node ([nodeId]),
 * whose out-of-band work must finish before the run resumes from it. Stored as the JSON array in
 * `pipeline_run.awaiting`.
 *
 * Today a run parks on at most one await at a time; the set shape is forward-compatible with
 * concurrent suspends.
 */
@Serializable
data class PipelineRunAwait(
    val nodeId: String,
)
