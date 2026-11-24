package bosca.chat.message

import bosca.chat.model.ChatMessage
import bosca.chat.model.ChatMessageSendResult
import bosca.chat.state.ChatReactionStore
import bosca.communications.model.MessageContent
import bosca.nats.NatsConnectionPool
import bosca.serialization.UUID
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.PublishOptions
import io.nats.client.PullSubscribeOptions
import io.nats.client.PushSubscribeOptions
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import io.nats.client.api.StreamConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.time.Duration

import bosca.chat.message.ChatMessageStore

class NatsChatMessageStore(
    private val nats: NatsConnectionPool,
    private val json: Json,
) : ChatMessageStore {
    private val streamName = "CHAT"
    private val subjectPattern = "bosca.chat.v1.channels.*.messages"
    private val initMutex = Mutex()
    @Volatile private var initialized = false

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                val jsm = nats.systemConnection().jetStreamManagement()
                try {
                    val streamInfo = jsm.getStreamInfo(streamName)
                    if (streamInfo.config.duplicateWindow != MESSAGE_DEDUPLICATION_WINDOW) {
                        jsm.updateStream(
                            StreamConfiguration.builder(streamInfo.config)
                                .duplicateWindow(MESSAGE_DEDUPLICATION_WINDOW)
                                .build()
                        )
                    }
                } catch (e: JetStreamApiException) {
                    if (e.apiErrorCode != STREAM_NOT_FOUND_ERROR) throw e
                    try {
                        jsm.addStream(
                            StreamConfiguration.builder()
                                .name(streamName)
                                .subjects(subjectPattern)
                                .duplicateWindow(MESSAGE_DEDUPLICATION_WINDOW)
                                .build()
                        )
                    } catch (creationFailure: JetStreamApiException) {
                        // Initialization is process-local, so another replica may create the
                        // stream between our lookup and add. Only accept the failed add when a
                        // follow-up lookup proves the required stream now exists.
                        try {
                            jsm.getStreamInfo(streamName)
                        } catch (verificationFailure: Exception) {
                            creationFailure.addSuppressed(verificationFailure)
                            throw creationFailure
                        }
                    }
                }
            }
            initialized = true
        }
    }

    override fun subscribe(channelId: UUID): Flow<ChatMessage> = flow {
        val subject = "bosca.chat.v1.channels.$channelId.messages"
        val deletionSubject = "$subject.deleted"
        ensureInitialized()
        val connection = nats.openConnection()
        try {
            val options = PushSubscribeOptions.builder()
                .stream(streamName)
                .configuration(
                    ConsumerConfiguration.builder()
                        .deliverPolicy(DeliverPolicy.New)
                        .build()
                    )
                .build()

            connection.subscribeJetStreamAndCore(subject, options, deletionSubject) {
                connection.flush(SUBSCRIPTION_READY_TIMEOUT)
            }.collect { message ->
                try {
                    if (message.subject == deletionSubject) {
                        emit(json.decodeFromString<ChatMessage>(message.data.toString(Charsets.UTF_8)))
                        return@collect
                    }
                    val chatMessage = json.decodeFromString<ChatMessage>(message.data.toString(Charsets.UTF_8))
                    val meta = message.metaData()
                    emit(chatMessage.copy(
                        sequence = meta.streamSequence(),
                        timestamp = java.time.OffsetDateTime.ofInstant(
                            meta.timestamp().toInstant(),
                            java.time.ZoneId.systemDefault()
                        )
                    ))
                    message.ack()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // JetStream messages remain unacknowledged so the active consumer can redeliver
                    // them; malformed core tombstones are skipped without killing the subscription.
                    log.warn("Failed to process chat event on subject '{}'", message.subject, e)
                }
            }
        } finally {
            connection.close()
        }
    }.retryNatsSubscription("chat messages for channel $channelId").flowOn(Dispatchers.IO)

    override suspend fun sendMessage(
        channelId: UUID,
        senderId: UUID,
        clientId: UUID,
        content: List<MessageContent>,
        attributes: JsonElement?,
        parentSequence: Long?,
    ): ChatMessageSendResult {
        ensureInitialized()
        val subject = "bosca.chat.v1.channels.$channelId.messages"
        val pendingMessage = ChatMessage(
            sequence = 0,
            timestamp = java.time.OffsetDateTime.now(),
            senderId = senderId,
            clientId = clientId,
            content = content,
            attributes = attributes,
            parentSequence = parentSequence,
        )
        val payload = json.encodeToString(
            ChatMessage.serializer(),
            pendingMessage,
        )
        val result = withContext(Dispatchers.IO) {
            val connection = nats.openConnection(subject)
            try {
                connection.withConnection { physicalConnection ->
                    val ack = physicalConnection.jetStream().publish(
                        subject,
                        payload.toByteArray(Charsets.UTF_8),
                        PublishOptions.builder()
                            .messageId("$channelId:$senderId:$clientId")
                            .build(),
                    )
                    ChatMessageSendResult(
                        sequence = ack.seqno,
                        duplicate = ack.isDuplicate,
                    )
                }
            } finally {
                connection.close()
            }
        }
        return result
    }

    override suspend fun getMessages(
        channelId: UUID,
        before: Long?,
        after: Long?,
        limit: Int
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val subject = "bosca.chat.v1.channels.$channelId.messages"
        val builder = ConsumerConfiguration.builder()
            .filterSubject(subject)
        val connection = nats.openConnection(subject)
        try {
            val sliced = connection.withConnection { physicalConnection ->
                if (after != null) {
                    builder.deliverPolicy(DeliverPolicy.ByStartSequence)
                        .startSequence(after + 1)
                } else if (before != null) {
                    builder.deliverPolicy(DeliverPolicy.ByStartSequence)
                        .startSequence((before - limit.toLong() * 10).coerceAtLeast(1L))
                } else {
                    val si = try {
                        physicalConnection.jetStreamManagement().getStreamInfo(streamName)
                    } catch (e: JetStreamApiException) {
                        if (e.apiErrorCode == STREAM_NOT_FOUND_ERROR) {
                            return@withConnection emptyList()
                        }
                        throw e
                    }
                    val lastSeq = si.streamState.lastSequence
                    builder.deliverPolicy(DeliverPolicy.ByStartSequence)
                        .startSequence((lastSeq - limit.toLong() * 10).coerceAtLeast(1L))
                }

                val config = builder.build()
                val sub = physicalConnection.jetStream().subscribe(
                    subject,
                    PullSubscribeOptions.builder().configuration(config).build(),
                )

                try {
                    val messages = mutableListOf<ChatMessage>()
                    val natsMessages = sub.fetch(limit * 5, Duration.ofMillis(500))
                    for (msg in natsMessages) {
                        val chatMessage = json.decodeFromString<ChatMessage>(msg.data.toString(Charsets.UTF_8))
                        val meta = msg.metaData()
                        val sequence = meta.streamSequence()

                        if (before != null && sequence >= before) continue

                        messages.add(
                            chatMessage.copy(
                                sequence = sequence,
                                timestamp = java.time.OffsetDateTime.ofInstant(
                                    meta.timestamp().toInstant(),
                                    java.time.ZoneId.systemDefault(),
                                ),
                            )
                        )
                        msg.ack()
                    }

                    if (before != null || after == null) {
                        messages.takeLast(limit)
                    } else {
                        messages.take(limit)
                    }
                } finally {
                    sub.unsubscribe()
                }
            }
            if (sliced.isEmpty()) return@withContext sliced

            sliced
        } finally {
            connection.close()
        }
    }

    override suspend fun getMessage(channelId: UUID, sequence: Long): ChatMessage? {
        ensureInitialized()
        val subject = "bosca.chat.v1.channels.$channelId.messages"
        val connection = nats.openConnection(subject)
        try {
            return withContext(Dispatchers.IO) {
                connection.withConnection { physicalConnection ->
                    storedMessage(physicalConnection, channelId, sequence)
                }
            }
        } finally {
            connection.close()
        }
    }

    override suspend fun deleteMessage(channelId: UUID, sequence: Long): Boolean {
        ensureInitialized()
        val subject = "bosca.chat.v1.channels.$channelId.messages"
        val connection = nats.openConnection(subject)
        val deleted = try {
            withContext(Dispatchers.IO) {
                connection.withConnection { physicalConnection ->
                    val message = storedMessage(physicalConnection, channelId, sequence)
                        ?: return@withConnection false
                    if (!physicalConnection.jetStreamManagement().deleteMessage(streamName, sequence)) {
                        return@withConnection false
                    }
                    val tombstone = message.copy(
                        content = emptyList(),
                        attributes = null,
                        reactions = emptyList(),
                        deleted = true,
                    )
                    physicalConnection.publish(
                        "$subject.deleted",
                        json.encodeToString(ChatMessage.serializer(), tombstone).toByteArray(Charsets.UTF_8),
                    )
                    true
                }
            }
        } finally {
            connection.close()
        }
        return deleted
    }

    private fun storedMessage(connection: Connection, channelId: UUID, sequence: Long): ChatMessage? {
        val messageInfo = try {
            connection.jetStreamManagement().getMessage(streamName, sequence)
        } catch (e: JetStreamApiException) {
            if (e.apiErrorCode == MESSAGE_NOT_FOUND_ERROR || e.apiErrorCode == STREAM_NOT_FOUND_ERROR) return null
            throw e
        } ?: return null
        if (messageInfo.subject != "bosca.chat.v1.channels.$channelId.messages") return null
        val data = checkNotNull(messageInfo.data) { "chat message $sequence has no payload" }
        val timestamp = checkNotNull(messageInfo.time) { "chat message $sequence has no timestamp" }
        return json.decodeFromString<ChatMessage>(data.toString(Charsets.UTF_8)).copy(
            sequence = sequence,
            timestamp = timestamp.toOffsetDateTime(),
        )
    }

    private fun <T> Flow<T>.retryNatsSubscription(name: String): Flow<T> = retryWhen { cause, attempt ->
        if (cause is CancellationException) return@retryWhen false
        val delayMs = ((attempt + 1).coerceAtMost(10)) * SUBSCRIPTION_RETRY_DELAY_MS
        log.warn("NATS {} subscription failed; retrying in {}ms", name, delayMs, cause)
        delay(delayMs)
        true
    }

    private companion object {
        const val STREAM_NOT_FOUND_ERROR = 10059
        const val MESSAGE_NOT_FOUND_ERROR = 10037
        const val SUBSCRIPTION_RETRY_DELAY_MS = 100L
        val SUBSCRIPTION_READY_TIMEOUT: Duration = Duration.ofSeconds(5)
        val MESSAGE_DEDUPLICATION_WINDOW: Duration = Duration.ofHours(24)
        val log = LoggerFactory.getLogger(NatsChatMessageStore::class.java)
    }
}
