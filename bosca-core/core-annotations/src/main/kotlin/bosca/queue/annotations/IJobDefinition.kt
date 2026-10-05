package bosca.queue.annotations

import kotlin.uuid.Uuid

/**
 * Marker interface for job definition payload types.
 *
 * Implementations carry the data needed to execute a specific kind of job.
 * Referenced by [@JobDefinition][bosca.queue.annotations.JobDefinition] to associate
 * a job executor with its payload type.
 */
interface IJobDefinition

/**
 * Job definition payload that targets a specific metadata entry at a specific version.
 *
 * Used by jobs that operate on content metadata (e.g. indexing, transformation, publishing).
 */
interface IMetadataJobDefinition : IJobDefinition {

    /** The ID of the metadata entry this job should process. */
    val id: Uuid

    /** The metadata version to process, or `null` to target the latest version. */
    val version: Int?
}

/**
 * Job definition payload that targets a specific collection.
 *
 * Used by jobs that operate on content collections (e.g. publishing, index rebuilds).
 */
interface ICollectionJobDefinition : IJobDefinition {

    /** The ID of the collection this job should process. */
    val id: Uuid
}