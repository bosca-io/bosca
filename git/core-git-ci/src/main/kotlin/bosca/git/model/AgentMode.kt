package bosca.git.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Operating mode of a build agent.
 */
@DbMapper(AgentModeMapper::class)
@Serializable
enum class AgentMode {
    RUNNER,
    ORCHESTRATOR
}

object AgentModeMapper : EnumMapper<AgentMode>({ AgentMode.valueOf(it.uppercase()) })

/**
 * Current status of a build agent.
 */
@DbMapper(AgentStatusMapper::class)
@Serializable
enum class AgentStatus {
    ONLINE,
    OFFLINE,
    BUSY,
    DRAINING
}

object AgentStatusMapper : EnumMapper<AgentStatus>({ AgentStatus.valueOf(it.uppercase()) })
