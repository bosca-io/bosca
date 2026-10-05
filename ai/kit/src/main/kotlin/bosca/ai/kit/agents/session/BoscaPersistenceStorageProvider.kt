package bosca.ai.kit.agents.session

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import bosca.serialization.UUID

/**
 * Bridges Koog's [PersistenceStorageProvider] (what the `Persistence` feature checkpoints through) to
 * Kit's own [KitSessionService] — so a Kit session's snapshots are saved and restored via Bosca's
 * storage rather than Koog's defaults. No filtering is supported (the [Unit] filter is ignored).
 */
class BoscaPersistenceStorageProvider(
    private val sessions: KitSessionService,
) : PersistenceStorageProvider<Unit> {

    override suspend fun getCheckpoints(sessionId: String, filter: Unit?): List<AgentCheckpointData> {
        val id = UUID.parse(sessionId)
        return sessions.getCheckpoints(id)
    }

    override suspend fun saveCheckpoint(sessionId: String, agentCheckpointData: AgentCheckpointData) {
        val id = UUID.parse(sessionId)
        sessions.saveCheckpoint(id, agentCheckpointData)
    }

    override suspend fun getLatestCheckpoint(sessionId: String, filter: Unit?): AgentCheckpointData? {
        val id = UUID.parse(sessionId)
        return sessions.getLatestCheckpoint(id)
    }
}
