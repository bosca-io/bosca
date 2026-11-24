package bosca.chat.state

import bosca.chat.model.PresenceUpdateEvent
import bosca.db.ConnectionPool
import bosca.serialization.UUID

class PostgresChatPresenceStore(private val pool: ConnectionPool) : ChatPresenceStore {
    override suspend fun setPresence(profileId: UUID, status: String) { chatUpdate(pool, "insert into chat.presence(profile_id, status, expires) values (?::uuid, ?, clock_timestamp() + interval '5 minutes') on conflict (profile_id) do update set status = excluded.status, expires = excluded.expires", profileId.toString(), status) }
    override suspend fun clearPresence(profileId: UUID) { chatUpdate(pool, "delete from chat.presence where profile_id = ?::uuid", profileId.toString()) }
    override suspend fun getPresence(profileId: UUID): PresenceUpdateEvent? = withChatConnection(pool) { m ->
        m.useStatement("select status from chat.presence where profile_id = ?::uuid and expires > clock_timestamp()") { stmt ->
            stmt.setString(1, profileId.toString()); stmt.executeQuery().use { if (it.next()) PresenceUpdateEvent(profileId, it.getString(1)) else null }
        }
    }
    override suspend fun getPresences(profileIds: Collection<UUID>): Map<UUID, PresenceUpdateEvent> {
        val ids = profileIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return withChatConnection(pool) { m ->
            m.useStatement("select profile_id::text, status from chat.presence where profile_id in (${ids.joinToString { "?::uuid" }}) and expires > clock_timestamp()") { stmt ->
                ids.forEachIndexed { index, id -> stmt.setString(index + 1, id.toString()) }
                stmt.executeQuery().use { result ->
                    buildMap { while (result.next()) UUID.parse(result.getString(1)).let { put(it, PresenceUpdateEvent(it, result.getString(2))) } }
                }
            }
        }
    }
}
