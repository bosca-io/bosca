package bosca.chat.state

import bosca.chat.model.UserTypingEvent
import bosca.db.ConnectionPool
import bosca.serialization.UUID

class PostgresChatTypingStore(private val pool: ConnectionPool) : ChatTypingStore {
    override suspend fun setTyping(channelId: UUID, profileId: UUID) {
        chatUpdate(pool, "insert into chat.typing(channel_id, profile_id, expires) values (?::uuid, ?::uuid, clock_timestamp() + interval '5 minutes') on conflict (channel_id, profile_id) do update set expires = excluded.expires", channelId.toString(), profileId.toString())
    }
    override suspend fun clearTyping(channelId: UUID, profileId: UUID) { chatUpdate(pool, "delete from chat.typing where channel_id = ?::uuid and profile_id = ?::uuid", channelId.toString(), profileId.toString()) }
    override suspend fun clearProfile(profileId: UUID) { chatUpdate(pool, "delete from chat.typing where profile_id = ?::uuid", profileId.toString()) }
    override suspend fun clearChannel(channelId: UUID) { chatUpdate(pool, "delete from chat.typing where channel_id = ?::uuid", channelId.toString()) }
    override suspend fun activeTypers(channelId: UUID): List<UserTypingEvent> = withChatConnection(pool) { m ->
        m.useStatement("select profile_id::text from chat.typing where channel_id = ?::uuid and expires > clock_timestamp()") { stmt ->
            stmt.setString(1, channelId.toString()); stmt.executeQuery().use { result -> buildList { while (result.next()) add(UserTypingEvent(channelId, UUID.parse(result.getString(1)), true)) } }
        }
    }
}
