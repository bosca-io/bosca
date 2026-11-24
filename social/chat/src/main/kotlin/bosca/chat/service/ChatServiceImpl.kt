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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Chat domain orchestration with selectable message and state persistence.
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
    private val messageStore: bosca.chat.message.ChatMessageStore,
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
    override suspend fun shutdown() {
        val subscriptions = subscriptionScope.coroutineContext[kotlinx.coroutines.Job]
        subscriptions?.cancel()
        subscriptions?.join()
    }

    override suspend fun getById(id: UUID): ChatChannel? {
        return chatChannelRepository.getById(id)
    }

    override fun subscribe(channelId: UUID, profileId: UUID): Flow<ChatMessage> =
        subscribeInternal(channelId).closeWhenMemberRemoved(channelId, profileId)

    private fun subscribeInternal(channelId: UUID): Flow<ChatMessage> = messageStore.subscribe(channelId)

    override fun subscribeTyping(channelId: UUID, profileId: UUID): Flow<UserTypingEvent> =
        subscribeTypingInternal(channelId).closeWhenMemberRemoved(channelId, profileId)

    private fun subscribeTypingInternal(channelId: UUID): Flow<UserTypingEvent> =
        pubSubService.subscribe("bosca.chat.v1.channels.$channelId.typing", UserTypingEvent.serializer()).map { it.message }

    override fun subscribePresence(channelId: UUID, profileId: UUID): Flow<PresenceUpdateEvent> =
        subscribePresenceInternal(channelId).closeWhenMemberRemoved(channelId, profileId)

    private fun subscribePresenceInternal(channelId: UUID): Flow<PresenceUpdateEvent> =
        pubSubService.subscribe("bosca.chat.v1.channels.$channelId.presence", PresenceUpdateEvent.serializer()).map { it.message }

    override suspend fun sendTyping(channelId: UUID, profileId: UUID, isTyping: Boolean) {
        // Update the durable KV state first so a freshly-connected client
        // sees the right snapshot, then publish the realtime event for
        // already-subscribed peers.
        if (isTyping) typingStore.setTyping(channelId, profileId)
        else typingStore.clearTyping(channelId, profileId)

        pubSubService.publish("bosca.chat.v1.channels.$channelId.typing", UserTypingEvent.serializer(), UserTypingEvent(channelId, profileId, isTyping))
    }

    override suspend fun getActiveTypers(channelId: UUID): List<UserTypingEvent> =
        typingStore.activeTypers(channelId)

    override suspend fun setPresence(profileId: UUID, status: String) {
        // Same pattern as typing — write KV state then fan out via pub/sub.
        presenceStore.setPresence(profileId, status)
        getChannels(profileId).forEach { channel ->
            pubSubService.publish("bosca.chat.v1.channels.${channel.id}.presence", PresenceUpdateEvent.serializer(), PresenceUpdateEvent(profileId, status))
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
        val result = messageStore.sendMessage(channelId, senderId, clientId, content, attributes, parentSequence)
        if (!result.duplicate) {
            ChatMessageSentEvent(
                channelId = channelId,
                senderId = senderId,
                sequence = result.sequence,
                content = content,
                sentAt = java.time.OffsetDateTime.now(),
                attributes = attributes
            ).dispatch()
        }
        return result
    }

    override suspend fun getMessages(channelId: UUID, before: Long?, after: Long?, limit: Int): List<ChatMessage> {
        val messages = messageStore.getMessages(channelId, before, after, limit)
        val reactions = reactionStore.getForMessages(channelId, messages.map { it.sequence })
        return messages.map { it.copy(reactions = reactions[it.sequence].orEmpty()) }
    }
    override suspend fun getMessage(channelId: UUID, sequence: Long): ChatMessage? = messageStore.getMessage(channelId, sequence)
    override suspend fun deleteMessage(channelId: UUID, sequence: Long): Boolean {
        val deleted = messageStore.deleteMessage(channelId, sequence)
        if (deleted) reactionStore.clearMessage(channelId, sequence)
        return deleted
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

    private fun subscribeReactionsInternal(channelId: UUID): Flow<MessageReactionEvent> =
        pubSubService.subscribe("bosca.chat.v1.channels.$channelId.reactions", MessageReactionEvent.serializer()).map { it.message }

    private suspend fun publishReactionEvent(
        channelId: UUID,
        sequence: Long,
        profileId: UUID,
        emoji: String,
        added: Boolean,
    ) {
        pubSubService.publish("bosca.chat.v1.channels.$channelId.reactions", MessageReactionEvent.serializer(), MessageReactionEvent(channelId, sequence, profileId, emoji, added))
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

    private companion object {
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
