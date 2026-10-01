package bosca.scripting.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Job payload for syncing scripts linked to files in a pushed git commit
 * range. Enqueued from [bosca.scripting.service.ScriptingGitPushListener]
 * on each `bosca.git.push` event, executed by [ScriptSourceSyncExecutor].
 */
@Serializable
data class ScriptSourceSyncJob(
    @Contextual val repositoryId: UUID,
    val ref: String,
    val beforeSha: String,
    val afterSha: String,
) : IJobDefinition
