@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.git

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * GraphQL input for one entry in an `AgentRepoMutation.backfill` request. Each entry
 * names a DB row by id and the repository path it should be written to. Maps 1:1 to
 * the internal [BackfillEntry] (this is just the wire shape).
 */
@Serializable
data class AgentRepoBackfillEntry(
    val entityType: AgentEntityType,
    @Contextual
    val entityId: Uuid,
    val gitPath: String,
)
