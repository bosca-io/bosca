package bosca.chat.state

import bosca.db.ConnectionPool
import bosca.serialization.UUID

class PostgresChatReadStateStore(private val pool: ConnectionPool) : ChatReadStateStore {
    override suspend fun set(channelId: UUID, profileId: UUID, sequence: Long) {
        chatUpdate(pool, "insert into chat.read_positions(channel_id, profile_id, sequence) values (?::uuid, ?::uuid, ?) on conflict (channel_id, profile_id) do update set sequence = excluded.sequence", channelId.toString(), profileId.toString(), sequence)
    }
    override suspend fun get(channelId: UUID, profileId: UUID): Long? = withChatConnection(pool) { m ->
        m.useStatement("select sequence from chat.read_positions where channel_id = ?::uuid and profile_id = ?::uuid") { stmt ->
            stmt.setString(1, channelId.toString()); stmt.setString(2, profileId.toString())
            stmt.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }
    }
    override suspend fun getAll(memberships: Collection<Pair<UUID, UUID>>): Map<Pair<UUID, UUID>, Long> {
        val keys = memberships.distinct()
        if (keys.isEmpty()) return emptyMap()
        return withChatConnection(pool) { m ->
            m.useStatement("select channel_id::text, profile_id::text, sequence from chat.read_positions where (channel_id, profile_id) in (${keys.joinToString { "(?::uuid, ?::uuid)" }})") { stmt ->
                keys.forEachIndexed { index, (channelId, profileId) ->
                    stmt.setString(index * 2 + 1, channelId.toString()); stmt.setString(index * 2 + 2, profileId.toString())
                }
                stmt.executeQuery().use { result ->
                    buildMap { while (result.next()) put(UUID.parse(result.getString(1)) to UUID.parse(result.getString(2)), result.getLong(3)) }
                }
            }
        }
    }
    override suspend fun clear(channelId: UUID, profileId: UUID) { chatUpdate(pool, "delete from chat.read_positions where channel_id = ?::uuid and profile_id = ?::uuid", channelId.toString(), profileId.toString()) }
    override suspend fun clearProfile(profileId: UUID) { chatUpdate(pool, "delete from chat.read_positions where profile_id = ?::uuid", profileId.toString()) }
    override suspend fun clearChannel(channelId: UUID) { chatUpdate(pool, "delete from chat.read_positions where channel_id = ?::uuid", channelId.toString()) }
}
