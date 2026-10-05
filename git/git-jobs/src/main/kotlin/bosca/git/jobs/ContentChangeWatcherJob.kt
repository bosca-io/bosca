package bosca.git.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Job payload for scanning a push to a SCRIPT_PROJECT repository
 * to detect which source files changed. Emits [ScriptSourceUpdatedEvent]
 * for each affected script so the scripting engine can invalidate its cache.
 *
 * The watcher compares the tree at [beforeSha] against [afterSha] to identify
 * changed paths, then maps those paths to script keys using the repository's convention:
 * - SCRIPT_PROJECT: `scripts/{key}/source.{ext}` -> emits per-script event
 */
@Serializable
data class ContentChangeWatcherJob(
    val repositoryId: UUID,
    val ref: String,
    val beforeSha: String,
    val afterSha: String
) : IJobDefinition
