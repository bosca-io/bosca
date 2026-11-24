package bosca.chat.message

import bosca.chat.model.ChatMessage
import bosca.chat.model.ChatMessageSendResult
import bosca.chat.state.withChatConnection
import bosca.communications.model.MessageContent
import bosca.db.*
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.OffsetDateTime

/** Ordered durable chat history with transactional live publication and 24-hour client deduplication. */
class PostgresChatMessageStore(
    private val pool: ConnectionPool,
    private val pubsub: PubSubService,
    private val json: Json,
) : ChatMessageStore {
    private val scope = CoroutineScope(SupervisorJob() + DatabaseDispatcher)
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var lastCleanup = System.nanoTime()
    private val relay = scope.launch {
        while (isActive) {
            try {
                val sent = relayEvents()
                if (sent) continue
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { LoggerFactory.getLogger(javaClass).warn("Chat event relay failed; durable events remain pending", e) }
            withTimeoutOrNull(1.seconds) { wakeup.receive() }
        }
    }

    /** Stops event delivery before its database pool is closed. Pending events remain durable. */
    suspend fun shutdown() { scope.cancel(); relay.join() }

    private suspend fun enqueueEvent(manager: ConnectionManager, channelId: UUID, message: ChatMessage) {
        manager.useStatement("insert into chat.message_events(channel_id, payload) values (?::uuid, ?::jsonb)") { stmt ->
            stmt.setString(1, channelId.toString()); stmt.setString(2, json.encodeToString(ChatMessage.serializer(), message)); stmt.executeUpdate()
        }
        afterCommit { wakeup.trySend(Unit) }
    }

    private suspend fun relayEvents(): Boolean = pool.connection().use { manager -> withContext(manager.asCoroutineContext()) { transaction {
        val leader = manager.useStatement("select pg_try_advisory_xact_lock(hashtext('bosca.chat.message-events'))") { stmt -> stmt.executeQuery().use { it.next(); it.getBoolean(1) } }
        if (!leader) return@transaction false
        if (System.nanoTime() - lastCleanup > 60_000_000_000L) {
            for (table in listOf("typing", "presence", "message_deduplication")) {
                manager.useStatement("delete from chat.$table where expires <= clock_timestamp()") { it.executeUpdate() }
            }
            lastCleanup = System.nanoTime()
        }
        val events = manager.useStatement("select id, channel_id::text, payload::text from chat.message_events order by id for update limit 100") { stmt ->
            stmt.executeQuery().use { result -> buildList { while (result.next()) add(Triple(result.getLong(1), UUID.parse(result.getString(2)), result.getString(3))) } }
        }
        for ((id, channelId, payload) in events) {
            pubsub.publish(topic(channelId), ChatMessage.serializer(), json.decodeFromString(ChatMessage.serializer(), payload))
            manager.useStatement("delete from chat.message_events where id = ?") { stmt -> stmt.setLong(1, id); stmt.executeUpdate() }
        }
        events.isNotEmpty()
    } } }

    private fun topic(channelId: UUID) = "bosca.chat.v1.channels.$channelId.messages"
    override fun subscribe(channelId: UUID): Flow<ChatMessage> =
        pubsub.subscribe(topic(channelId), ChatMessage.serializer()).map { it.message }

    override suspend fun sendMessage(channelId: UUID, senderId: UUID, clientId: UUID, content: List<MessageContent>, attributes: JsonElement?, parentSequence: Long?): ChatMessageSendResult =
        withChatConnection(pool) { manager -> withContext(manager.asCoroutineContext()) { transaction {
            // Serialize sequence assignment through commit so a later committed message cannot overtake an earlier one.
            manager.useStatement("select value from chat.message_sequence where singleton for update") { it.executeQuery().close() }
            val duplicate = manager.useStatement("select sequence from chat.message_deduplication where channel_id = ?::uuid and sender_id = ?::uuid and client_id = ?::uuid and expires > clock_timestamp()") { stmt ->
                stmt.setString(1, channelId.toString()); stmt.setString(2, senderId.toString()); stmt.setString(3, clientId.toString())
                stmt.executeQuery().use { if (it.next()) it.getLong(1) else null }
            }
            if (duplicate != null) return@transaction ChatMessageSendResult(duplicate, true)
            val sequence = manager.useStatement("update chat.message_sequence set value = value + 1 where singleton returning value") { stmt -> stmt.executeQuery().use { it.next(); it.getLong(1) } }
            val message = ChatMessage(sequence, OffsetDateTime.now(), senderId, clientId, content, attributes, parentSequence)
            manager.useStatement("insert into chat.messages(sequence, channel_id, sender_id, client_id, created, payload) values (?, ?::uuid, ?::uuid, ?::uuid, ?, ?::jsonb)") { stmt ->
                stmt.setLong(1, sequence); stmt.setString(2, channelId.toString()); stmt.setString(3, senderId.toString()); stmt.setString(4, clientId.toString())
                stmt.setObject(5, message.timestamp); stmt.setString(6, json.encodeToString(ChatMessage.serializer(), message)); stmt.executeUpdate()
            }
            manager.useStatement("""
                insert into chat.message_deduplication(channel_id, sender_id, client_id, sequence, expires)
                values (?::uuid, ?::uuid, ?::uuid, ?, clock_timestamp() + interval '24 hours')
                on conflict (channel_id, sender_id, client_id) do update set sequence = excluded.sequence, expires = excluded.expires
            """.trimIndent()) { stmt ->
                stmt.setString(1, channelId.toString()); stmt.setString(2, senderId.toString()); stmt.setString(3, clientId.toString()); stmt.setLong(4, sequence); stmt.executeUpdate()
            }
            enqueueEvent(manager, channelId, message)
            ChatMessageSendResult(sequence, false)
        } } }

    override suspend fun getMessages(channelId: UUID, before: Long?, after: Long?, limit: Int): List<ChatMessage> {
        require(limit > 0)
        val reverse = before != null || after == null
        return withChatConnection(pool) { manager ->
            manager.useStatement("select payload::text from chat.messages where channel_id = ?::uuid and (?::bigint is null or sequence < ?) and (?::bigint is null or sequence > ?) order by sequence ${if (reverse) "desc" else "asc"} limit ?") { stmt ->
                stmt.setString(1, channelId.toString()); stmt.setObject(2, before); stmt.setObject(3, before); stmt.setObject(4, after); stmt.setObject(5, after); stmt.setInt(6, limit)
                stmt.executeQuery().use { result -> buildList { while (result.next()) add(json.decodeFromString(ChatMessage.serializer(), result.getString(1))) } }.let { if (reverse) it.reversed() else it }
            }
        }
    }
    override suspend fun getMessage(channelId: UUID, sequence: Long): ChatMessage? = withChatConnection(pool) { manager ->
        manager.useStatement("select payload::text from chat.messages where channel_id = ?::uuid and sequence = ?") { stmt ->
            stmt.setString(1, channelId.toString()); stmt.setLong(2, sequence)
            stmt.executeQuery().use { if (it.next()) json.decodeFromString(ChatMessage.serializer(), it.getString(1)) else null }
        }
    }
    override suspend fun deleteMessage(channelId: UUID, sequence: Long): Boolean = withChatConnection(pool) { manager -> withContext(manager.asCoroutineContext()) { transaction {
        val message = manager.useStatement("delete from chat.messages where channel_id = ?::uuid and sequence = ? returning payload::text") { stmt ->
            stmt.setString(1, channelId.toString()); stmt.setLong(2, sequence)
            stmt.executeQuery().use { if (it.next()) json.decodeFromString(ChatMessage.serializer(), it.getString(1)) else null }
        } ?: return@transaction false
        enqueueEvent(manager, channelId, message.copy(content = emptyList(), attributes = null, reactions = emptyList(), deleted = true))
        true
    } } }
}
