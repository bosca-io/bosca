package bosca.chat.service

import bosca.chat.events.ChatChannelCreatedEvent
import bosca.chat.events.ChatChannelJoinedEvent
import bosca.chat.events.ChatChannelMemberRemovedEvent
import bosca.chat.events.CHAT_CHANNEL_MEMBER_REMOVED_TOPIC
import bosca.chat.events.ChatProfileUnavailableEvent
import bosca.chat.events.CHAT_PROFILE_UNAVAILABLE_TOPIC
import bosca.chat.events.ChatMessageReactionAddedEvent
import bosca.chat.events.ChatMessageSentEvent
import bosca.chat.events.dispatch
import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatChannelPermission
import bosca.chat.model.ChatChannelRoles
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatObjectType
import bosca.chat.model.ChatMessage
import bosca.chat.model.ChatMessageSendResult
import bosca.chat.model.MessageReactionEvent
import bosca.chat.model.PresenceUpdateEvent
import bosca.chat.model.UserTypingEvent
import bosca.chat.repository.ChatChannelPermissionRepository
import bosca.chat.repository.ChatChannelRepository
import bosca.chat.state.ChatPresenceStore
import bosca.chat.state.ChatReactionStore
import bosca.chat.state.ChatReadStateStore
import bosca.chat.state.ChatTypingStore
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.graphql.Batch
import bosca.communications.model.MessageContent
import bosca.nats.NatsConnectionPool
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.PublishOptions
import io.nats.client.PullSubscribeOptions
import io.nats.client.PushSubscribeOptions
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import io.nats.client.api.StreamConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * NATS JetStream-backed implementation of [ChatService]. Messages are stored
 * as NATS JetStream records and high-frequency / ephemeral state — reactions,
 * typing indicators, presence, and per-user last-read pointers — lives in
 * dedicated NATS KV buckets. Channel metadata, memberships, and permissions
 * are still persisted in PostgreSQL because they're administrative truth
 * (auditable, joinable with profile rows).
 *
 * Realtime fanout for typing/presence/reactions stays on NATS pub/sub for
 * latency; the KV stores are the durable read-side that lets a freshly-
 * connected client render current state without waiting for the next event.
 *
 * Chat identity deliberately spans two layers. A [ChatChannelMember] records
 * the participating profile and its channel role, while the principal linked
 * to that profile belongs to the channel's system users/administrators groups
 * for ACL evaluation. Profile-scoped membership and principal-scoped access
 * are complementary parts of the model and must stay synchronized across
 * membership, role, and profile cleanup operations.
 */
@ServiceImplementation
class ChatServiceImpl(
    private val nats: NatsConnectionPool,
    private val chatChannelRepository: ChatChannelRepository,
    private val chatChannelPermissionRepository: ChatChannelPermissionRepository,
    private val reactionStore: ChatReactionStore,
    private val typingStore: ChatTypingStore,
    private val presenceStore: ChatPresenceStore,
    private val readStateStore: ChatReadStateStore,
    private val profileService: ProfileService,
    private val securityService: SecurityService,
    private val pubSubService: PubSubService,
    private val json: Json,
) : ChatService {

    private val streamName = "CHAT"
    private val subjectPattern = "bosca.chat.v1.channels.*.messages"
    private val initMutex = Mutex()
    private val subscriptionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memberRemovalEvents: SharedFlow<Message<ChatChannelMemberRemovedEvent>> by lazy {
        pubSubService.subscribe(
            CHAT_CHANNEL_MEMBER_REMOVED_TOPIC,
            ChatChannelMemberRemovedEvent.serializer(),
        ).shareIn(subscriptionScope, SharingStarted.Eagerly)
    }
    private val profileUnavailableEvents: SharedFlow<Message<ChatProfileUnavailableEvent>> by lazy {
        pubSubService.subscribe(
            CHAT_PROFILE_UNAVAILABLE_TOPIC,
            ChatProfileUnavailableEvent.serializer(),
        ).shareIn(subscriptionScope, SharingStarted.Eagerly)
    }
    @Volatile
    private var initialized = false

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

    override suspend fun getById(id: UUID): ChatChannel? {
        return chatChannelRepository.getById(id)
    }

    override fun subscribe(channelId: UUID, profileId: UUID): Flow<ChatMessage> =
        subscribeInternal(channelId).closeWhenMemberRemoved(channelId, profileId)

    private fun subscribeInternal(channelId: UUID): Flow<ChatMessage> = flow {
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

    override fun subscribeTyping(channelId: UUID, profileId: UUID): Flow<UserTypingEvent> =
        subscribeTypingInternal(channelId).closeWhenMemberRemoved(channelId, profileId)

    private fun subscribeTypingInternal(channelId: UUID): Flow<UserTypingEvent> = flow {
        val subject = "bosca.chat.v1.channels.$channelId.typing"
        val connection = nats.openConnection()
        try {
            connection.subscribe(subject).collect { message ->
                try {
                    emit(json.decodeFromString<UserTypingEvent>(message.data.toString(Charsets.UTF_8)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Ignoring malformed chat typing event on subject '{}'", message.subject, e)
                }
            }
        } finally {
            connection.close()
        }
    }.retryNatsSubscription("typing events for channel $channelId").flowOn(Dispatchers.IO)

    override fun subscribePresence(channelId: UUID, profileId: UUID): Flow<PresenceUpdateEvent> =
        subscribePresenceInternal(channelId).closeWhenMemberRemoved(channelId, profileId)

    private fun subscribePresenceInternal(channelId: UUID): Flow<PresenceUpdateEvent> = flow {
        val subject = "bosca.chat.v1.channels.$channelId.presence"
        val connection = nats.openConnection()
        try {
            connection.subscribe(subject).collect { message ->
                try {
                    emit(json.decodeFromString<PresenceUpdateEvent>(message.data.toString(Charsets.UTF_8)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Ignoring malformed chat presence event on subject '{}'", message.subject, e)
                }
            }
        } finally {
            connection.close()
        }
    }.retryNatsSubscription("presence events for channel $channelId").flowOn(Dispatchers.IO)

    override suspend fun sendTyping(channelId: UUID, profileId: UUID, isTyping: Boolean) {
        // Update the durable KV state first so a freshly-connected client
        // sees the right snapshot, then publish the realtime event for
        // already-subscribed peers.
        if (isTyping) typingStore.setTyping(channelId, profileId)
        else typingStore.clearTyping(channelId, profileId)

        val subject = "bosca.chat.v1.channels.$channelId.typing"
        val payload = json.encodeToString(UserTypingEvent.serializer(), UserTypingEvent(channelId, profileId, isTyping))
        val connection = nats.openConnection(subject)
        try {
            connection.publish(subject, payload.toByteArray(Charsets.UTF_8))
        } finally {
            connection.close()
        }
    }

    override suspend fun getActiveTypers(channelId: UUID): List<UserTypingEvent> =
        typingStore.activeTypers(channelId)

    override suspend fun setPresence(profileId: UUID, status: String) {
        // Same pattern as typing — write KV state then fan out via pub/sub.
        presenceStore.setPresence(profileId, status)
        val payload = json.encodeToString(
            PresenceUpdateEvent.serializer(),
            PresenceUpdateEvent(profileId = profileId, status = status),
        )
        val connection = nats.openConnection(profileId.toString())
        try {
            getChannels(profileId).forEach { channel ->
                connection.publish(
                    "bosca.chat.v1.channels.${channel.id}.presence",
                    payload.toByteArray(Charsets.UTF_8),
                )
            }
        } finally {
            connection.close()
        }
    }

    override suspend fun getPresence(profileId: UUID): PresenceUpdateEvent? =
        presenceStore.getPresence(profileId)

    override suspend fun getLastRead(channelId: UUID, profileId: UUID): Long? =
        readStateStore.get(channelId, profileId)

    override suspend fun getChannels(profileId: UUID): List<ChatChannel> {
        return chatChannelRepository.getChannelsByProfileId(profileId)
    }

    override suspend fun getChannelsByGroupId(groupId: UUID): List<ChatChannel> {
        return chatChannelRepository.getChannelsByGroupId(groupId)
    }

    override suspend fun joinChannel(
        channelId: UUID,
        profileId: UUID,
        role: String,
        notifyExistingMembers: Boolean,
    ) = transaction {
        val channel = checkNotNull(chatChannelRepository.getByIdForUpdate(channelId)) { "chat channel not found" }
        require(channel.type != ChatChannelType.DIRECT) {
            "direct channel participants cannot be changed"
        }
        addMembership(channelId, profileId, role, notifyExistingMembers)
    }

    private suspend fun addMembership(
        channelId: UUID,
        profileId: UUID,
        role: String,
        notifyExistingMembers: Boolean,
    ) {
        // Membership and participant state belong to the profile; authenticated access is
        // deliberately granted through the linked principal's channel system groups.
        val principalId = activePrincipalId(profileId)
            ?: error("chat profile $profileId is not eligible to participate in chat")
        val inserted = chatChannelRepository.addMemberIfAbsent(channelId, profileId, role) > 0
        val membershipRole = if (inserted) {
            role
        } else {
            checkNotNull(chatChannelRepository.getMember(channelId, profileId)) {
                "channel membership disappeared during join"
            }.role
        }
        ensurePrincipalGroup(principalId, channelUsersGroup(channelId))
        if (membershipRole == ChatChannelRoles.ADMIN) {
            ensurePrincipalGroup(principalId, channelAdministratorsGroup(channelId))
        }
        if (inserted && notifyExistingMembers) {
            ChatChannelJoinedEvent(UUID.random(), channelId, profileId, role).dispatch()
        }
    }

    override suspend fun canParticipate(profileId: UUID): Boolean = activePrincipalId(profileId) != null

    override suspend fun setMemberRole(
        channelId: UUID,
        profileId: UUID,
        role: String,
    ) = transaction {
        require(role == ChatChannelRoles.ADMIN || role == ChatChannelRoles.MEMBER) {
            "unsupported channel role: $role"
        }
        val channel = checkNotNull(chatChannelRepository.getByIdForUpdate(channelId)) { "chat channel not found" }
        require(channel.type != ChatChannelType.DIRECT) {
            "direct channel participants cannot be changed"
        }
        val member = checkNotNull(chatChannelRepository.getMember(channelId, profileId)) {
            "profile is not a channel member"
        }
        val profile = profileService.getById(profileId)
        check(!profile.isDeleted) { "deleted chat profile $profileId cannot have its role changed" }
        val principalId = checkNotNull(profile.principal) {
            "chat profile $profileId is not linked to an authenticated principal"
        }
        if (member.role == role) return@transaction
        check(chatChannelRepository.updateMemberRole(channelId, profileId, role) == 1) {
            "channel member role could not be changed"
        }
        val administratorsGroup = channelAdministratorsGroup(channelId)
        if (role == ChatChannelRoles.ADMIN) {
            ensurePrincipalGroup(principalId, administratorsGroup)
        } else if (!chatChannelRepository.hasOtherMemberForPrincipal(
                channelId,
                principalId,
                profileId,
                ChatChannelRoles.ADMIN,
            )
        ) {
            securityService.removePrincipalGroup(principalId, administratorsGroup.id)
        }
    }

    override suspend fun leaveChannel(channelId: UUID, profileId: UUID) = transaction {
        val channel = checkNotNull(chatChannelRepository.getByIdForUpdate(channelId)) { "chat channel not found" }
        require(channel.type != ChatChannelType.DIRECT) {
            "direct channel participants cannot be changed"
        }
        val membership = chatChannelRepository.getMember(channelId, profileId) ?: return@transaction
        removeMembership(channelId, membership)
    }

    override suspend fun removeMember(
        channelId: UUID,
        profileId: UUID,
    ) = transaction {
        val channel = checkNotNull(chatChannelRepository.getByIdForUpdate(channelId)) { "chat channel not found" }
        require(channel.type != ChatChannelType.DIRECT) {
            "direct channel participants cannot be changed"
        }
        val membership = checkNotNull(chatChannelRepository.getMember(channelId, profileId)) {
            "profile is not a channel member"
        }
        removeMembership(channelId, membership)
    }

    private suspend fun removeMembership(channelId: UUID, membership: ChatChannelMember) {
        val profileId = membership.profileId
        val principalId = checkNotNull(profileService.getById(profileId).principal) {
            "chat profile $profileId is not linked to an authenticated principal"
        }
        check(chatChannelRepository.removeMember(channelId, profileId) == 1) {
            "channel membership could not be removed"
        }
        if (!chatChannelRepository.hasOtherMemberForPrincipal(channelId, principalId, profileId, role = null)) {
            securityService.removePrincipalGroup(principalId, channelUsersGroup(channelId).id)
        }
        if (membership.role == ChatChannelRoles.ADMIN &&
            !chatChannelRepository.hasOtherMemberForPrincipal(
                channelId,
                principalId,
                profileId,
                ChatChannelRoles.ADMIN,
            )
        ) {
            securityService.removePrincipalGroup(principalId, channelAdministratorsGroup(channelId).id)
        }
        readStateStore.clear(channelId, profileId)
        typingStore.clearTyping(channelId, profileId)
        ChatChannelMemberRemovedEvent(channelId, profileId).dispatch()
    }

    private suspend fun activePrincipalId(profileId: UUID): UUID? {
        val profile = profileService.getById(profileId)
        if (profile.isDeleted) return null
        val principalId = profile.principal ?: return null
        securityService.getPrincipalById(principalId)?.takeIf { it.deletedAt == null } ?: return null
        return principalId
    }

    override suspend fun getMembers(channelId: UUID): List<ChatChannelMember> {
        return chatChannelRepository.getMembers(channelId)
    }

    override suspend fun getMember(channelId: UUID, profileId: UUID): ChatChannelMember? =
        chatChannelRepository.getMember(channelId, profileId)

    override suspend fun getMembers(channelId: UUID, profileIds: List<UUID>): List<ChatChannelMember> {
        if (profileIds.isEmpty()) return emptyList()
        return chatChannelRepository.getMembers(channelId, profileIds.distinct())
    }

    override suspend fun getMembers(
        channelId: UUID,
        offset: Long,
        limit: Int,
    ): List<ChatChannelMember> {
        require(offset >= 0) { "offset must not be negative" }
        require(limit > 0) { "limit must be positive" }
        return chatChannelRepository.getMembers(channelId, offset, limit)
    }

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
        if (!result.duplicate) {
            ChatMessageSentEvent(
                channelId = channelId,
                senderId = pendingMessage.senderId,
                sequence = result.sequence,
                content = pendingMessage.content,
                sentAt = pendingMessage.timestamp,
                attributes = pendingMessage.attributes,
            ).dispatch()
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

            // Reactions live in NATS KV (`chat-reactions` bucket) because the JetStream message
            // payload itself is immutable. Query only the returned messages' filtered subjects.
            val sequences = sliced.map { it.sequence }
            val reactionsBySequence = reactionStore.getForMessages(channelId, sequences)
            sliced.map { message ->
                val reactions = reactionsBySequence[message.sequence].orEmpty()
                if (reactions.isEmpty()) message else message.copy(reactions = reactions)
            }
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
        if (deleted) reactionStore.clearMessage(channelId, sequence)
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

    override suspend fun updateLastRead(channelId: UUID, profileId: UUID, sequence: Long) {
        readStateStore.set(channelId, profileId, sequence)
    }

    override suspend fun getPermissions(entity: ChatChannel): List<EntityPermission> {
        return chatChannelPermissionRepository.getPermissionsByChannelId(entity.id)
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = chatChannelPermissionRepository.getPermissionsByChannelIds(batch.keys)
        batch.setData(batch.keys, permissions.groupBy { it.entityId })
    }

    override suspend fun addPermission(permission: PermissionInput): EntityPermission {
        chatChannelPermissionRepository.addPermission(permission.entityId, permission.groupId, permission.action)
        return ChatChannelPermission(permission.entityId, permission.groupId, permission.action)
    }

    override suspend fun deletePermission(permission: PermissionInput): EntityPermission {
        chatChannelPermissionRepository.deletePermission(permission.entityId, permission.groupId, permission.action)
        return ChatChannelPermission(permission.entityId, permission.groupId, permission.action)
    }

    override suspend fun createDirectChannel(profileId1: UUID, profileId2: UUID): ChatChannel = transaction {
        require(profileId1 != profileId2) { "A direct channel requires two distinct profiles" }
        check(canParticipate(profileId1)) { "chat profile $profileId1 is not eligible to participate in chat" }
        check(canParticipate(profileId2)) { "chat profile $profileId2 is not eligible to participate in chat" }

        val channelId = directChannelId(profileId1, profileId2)
        val inserted = chatChannelRepository.createDirectChannelIfAbsent(channelId)
        val result = inserted ?: checkNotNull(
            chatChannelRepository.getByIdForUpdate(channelId),
        ) {
            "Direct channel for profiles $profileId1 and $profileId2 disappeared after concurrent creation"
        }
        check(result.type == ChatChannelType.DIRECT) {
            "Deterministic direct channel id $channelId is already used by a ${result.type} channel"
        }

        if (inserted == null) {
            val participants = chatChannelRepository.getMembers(result.id).mapTo(mutableSetOf()) { it.profileId }
            check(participants == setOf(profileId1, profileId2)) {
                "Deterministic direct channel id $channelId belongs to different participants"
            }
            return@transaction result
        }

        provisionChannelSecurity(result)
        addMembership(result.id, profileId1, "member", notifyExistingMembers = false)
        addMembership(result.id, profileId2, "member", notifyExistingMembers = false)
        ChatChannelCreatedEvent(result.id, null, result.name, result.type).dispatch()
        result
    }

    override suspend fun createChannel(
        groupId: UUID?,
        name: String,
        type: ChatChannelType,
        attributes: JsonElement?,
        dispatchCreatedEvent: Boolean,
        initialMemberProfileId: UUID?,
        initialMemberRole: String,
    ): ChatChannel {
        require(type != ChatChannelType.DIRECT) {
            "Direct channels must be created with two participants"
        }
        return transaction {
            val created = chatChannelRepository.createChannel(groupId, name, type, attributes)
            provisionChannelSecurity(created)
            if (initialMemberProfileId != null) {
                addMembership(
                    channelId = created.id,
                    profileId = initialMemberProfileId,
                    role = initialMemberRole,
                    notifyExistingMembers = false,
                )
            }
            if (dispatchCreatedEvent) {
                ChatChannelCreatedEvent(created.id, groupId, created.name, created.type).dispatch()
            }
            created
        }
    }

    override suspend fun getByObject(objectType: ChatObjectType, objectId: UUID): ChatChannel? {
        return chatChannelRepository.getByObject(objectType, objectId)
    }

    override suspend fun getOrCreateObjectChannel(
        objectType: ChatObjectType,
        objectId: UUID,
        name: String,
    ): ChatChannel = transaction {
        val inserted = chatChannelRepository.createObjectChannelIfAbsent(name, objectType, objectId)
        if (inserted != null) {
            provisionChannelSecurity(inserted)
            ChatChannelCreatedEvent(inserted.id, null, inserted.name, inserted.type).dispatch()
            return@transaction inserted
        }
        checkNotNull(chatChannelRepository.getByObject(objectType, objectId)) {
            "Object channel for $objectType $objectId disappeared after concurrent creation"
        }
    }

    override suspend fun addReaction(channelId: UUID, sequence: Long, profileId: UUID, emoji: String) {
        val message = getMessage(channelId, sequence) ?: return
        val reactionId = UUID.random()
        if (!reactionStore.add(channelId, sequence, profileId, reactionId, emoji)) return
        publishReactionEvent(channelId, sequence, profileId, emoji, added = true)
        ChatMessageReactionAddedEvent(
            reactionId = reactionId,
            channelId = channelId,
            sequence = sequence,
            reactorId = profileId,
            messageAuthorId = message.senderId,
            emoji = emoji,
        ).dispatch()
    }

    override suspend fun removeReaction(channelId: UUID, sequence: Long, profileId: UUID, emoji: String) {
        reactionStore.remove(channelId, sequence, profileId, emoji)
        publishReactionEvent(channelId, sequence, profileId, emoji, added = false)
    }

    override fun subscribeReactions(channelId: UUID, profileId: UUID): Flow<MessageReactionEvent> =
        subscribeReactionsInternal(channelId).closeWhenMemberRemoved(channelId, profileId)

    private fun subscribeReactionsInternal(channelId: UUID): Flow<MessageReactionEvent> = flow {
        val subject = "bosca.chat.v1.channels.$channelId.reactions"
        val connection = nats.openConnection()
        try {
            connection.subscribe(subject).collect { message ->
                try {
                    emit(json.decodeFromString<MessageReactionEvent>(message.data.toString(Charsets.UTF_8)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Ignoring malformed chat reaction event on subject '{}'", message.subject, e)
                }
            }
        } finally {
            connection.close()
        }
    }.retryNatsSubscription("reaction events for channel $channelId").flowOn(Dispatchers.IO)

    private suspend fun publishReactionEvent(
        channelId: UUID,
        sequence: Long,
        profileId: UUID,
        emoji: String,
        added: Boolean,
    ) {
        val subject = "bosca.chat.v1.channels.$channelId.reactions"
        val payload = json.encodeToString(MessageReactionEvent(channelId, sequence, profileId, emoji, added))
        val connection = nats.openConnection(subject)
        try {
            connection.publish(subject, payload.toByteArray(Charsets.UTF_8))
        } finally {
            connection.close()
        }
    }

    private fun <T> Flow<T>.closeWhenMemberRemoved(channelId: UUID, profileId: UUID): Flow<T> = callbackFlow {
        val removalWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            memberRemovalEvents.first { envelope ->
                val event = envelope.message
                event.profileId == profileId && event.channelId == channelId
            }
            close()
        }
        val unavailableWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            profileUnavailableEvents.first { it.message.profileId == profileId }
            close()
        }
        val isCurrentMember = withConnectionManager {
            chatChannelRepository.getMember(channelId, profileId) != null
        }
        if (!isCurrentMember) {
            removalWatcher.cancel()
            unavailableWatcher.cancel()
            close()
            return@callbackFlow
        }
        val upstream = launch(start = CoroutineStart.UNDISPATCHED) {
            this@closeWhenMemberRemoved.collect { send(it) }
            close()
        }
        awaitClose {
            removalWatcher.cancel()
            unavailableWatcher.cancel()
            upstream.cancel()
        }
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
        val log = LoggerFactory.getLogger(ChatServiceImpl::class.java)

        fun directChannelId(profileId1: UUID, profileId2: UUID): UUID {
            val participants = listOf(profileId1, profileId2).sortedBy(UUID::toString)
            val key = "bosca.chat.direct:${participants[0]}:${participants[1]}"
            return UUID.parse(
                java.util.UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString()
            )
        }

    }

    private suspend fun provisionChannelSecurity(channel: ChatChannel) {
        val users = securityService.addGroup(
            Group(
                name = ChatChannelSecurityGroups.usersName(channel.id),
                description = "Chat Channel: ${channel.name} Users",
                type = GroupType.SYSTEM,
            )
        )
        val administrators = securityService.addGroup(
            Group(
                name = ChatChannelSecurityGroups.administratorsName(channel.id),
                description = "Chat Channel: ${channel.name} Administrators",
                type = GroupType.SYSTEM,
            )
        )
        chatChannelPermissionRepository.addPermission(channel.id, users.id, PermissionAction.VIEW)
        chatChannelPermissionRepository.addPermission(channel.id, users.id, PermissionAction.EXECUTE)
        chatChannelPermissionRepository.addPermission(channel.id, administrators.id, PermissionAction.MANAGE)
    }

    private suspend fun channelUsersGroup(channelId: UUID): Group = checkNotNull(
        securityService.getGroupByName(ChatChannelSecurityGroups.usersName(channelId), GroupType.SYSTEM),
    ) { "chat channel $channelId users group not found" }

    private suspend fun channelAdministratorsGroup(channelId: UUID): Group = checkNotNull(
        securityService.getGroupByName(ChatChannelSecurityGroups.administratorsName(channelId), GroupType.SYSTEM),
    ) { "chat channel $channelId administrators group not found" }

    private suspend fun ensurePrincipalGroup(principalId: UUID, group: Group) {
        if (securityService.getPrincipalGroups(principalId).none { it.id == group.id }) {
            securityService.addPrincipalGroup(principalId, group.id)
        }
    }
}

internal object ChatChannelSecurityGroups {
    fun usersName(channelId: UUID): String = "chat.$channelId.users"

    fun administratorsName(channelId: UUID): String = "chat.$channelId.administrators"

    fun channelId(name: String): UUID? {
        val prefix = "chat."
        if (!name.startsWith(prefix)) return null
        val suffix = when {
            name.endsWith(".users") -> ".users"
            name.endsWith(".administrators") -> ".administrators"
            else -> return null
        }
        return runCatching {
            UUID.parse(name.removePrefix(prefix).removeSuffix(suffix))
        }.getOrNull()
    }
}
