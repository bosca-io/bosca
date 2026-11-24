package bosca.chat.state

import bosca.chat.model.MessageReaction
import bosca.db.ConnectionPool
import bosca.serialization.UUID

class PostgresChatReactionStore(private val pool: ConnectionPool) : ChatReactionStore {
    override suspend fun add(channelId: UUID, sequence: Long, profileId: UUID, reactionId: UUID, emoji: String): Boolean =
        chatUpdate(pool, "insert into chat.reactions(channel_id, sequence, profile_id, id, emoji) values (?::uuid, ?, ?::uuid, ?::uuid, ?) on conflict (channel_id, sequence, profile_id, emoji) do nothing", channelId.toString(), sequence, profileId.toString(), reactionId.toString(), emoji) > 0
    override suspend fun remove(channelId: UUID, sequence: Long, profileId: UUID, emoji: String) { chatUpdate(pool, "delete from chat.reactions where channel_id = ?::uuid and sequence = ? and profile_id = ?::uuid and emoji = ?", channelId.toString(), sequence, profileId.toString(), emoji) }
    override suspend fun getForMessage(channelId: UUID, sequence: Long): List<MessageReaction> = getForMessages(channelId, listOf(sequence))[sequence].orEmpty()
    override suspend fun getForMessages(channelId: UUID, sequences: Collection<Long>): Map<Long, List<MessageReaction>> {
        if (sequences.isEmpty()) return emptyMap()
        return withChatConnection(pool) { m ->
            m.useStatement("select sequence, profile_id::text, id::text, emoji from chat.reactions where channel_id = ?::uuid and sequence in (${sequences.joinToString { "?" }}) order by id") { stmt ->
                stmt.setString(1, channelId.toString()); sequences.forEachIndexed { index, value -> stmt.setLong(index + 2, value) }
                stmt.executeQuery().use { result ->
                    val grouped = mutableMapOf<Long, MutableList<MessageReaction>>()
                    while (result.next()) grouped.getOrPut(result.getLong(1)) { mutableListOf() }.add(MessageReaction(result.getString(4), UUID.parse(result.getString(2)), UUID.parse(result.getString(3))))
                    grouped
                }
            }
        }
    }
    override suspend fun clearMessage(channelId: UUID, sequence: Long) { chatUpdate(pool, "delete from chat.reactions where channel_id = ?::uuid and sequence = ?", channelId.toString(), sequence) }
    override suspend fun clearChannel(channelId: UUID) { chatUpdate(pool, "delete from chat.reactions where channel_id = ?::uuid", channelId.toString()) }
    override suspend fun clearProfile(profileId: UUID) { chatUpdate(pool, "delete from chat.reactions where profile_id = ?::uuid", profileId.toString()) }
}
