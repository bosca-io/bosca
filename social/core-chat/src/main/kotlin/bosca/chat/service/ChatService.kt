package bosca.chat.service

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatChannelRoles
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatObjectType
import bosca.chat.model.ChatMessage
import bosca.chat.model.ChatMessageSendResult
import bosca.chat.model.MessageReactionEvent
import bosca.chat.model.PresenceUpdateEvent
import bosca.chat.model.UserTypingEvent
import bosca.communications.model.MessageContent
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionInput
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * Manages real-time chat functionality including channel lifecycle, message delivery,
 * member management, typing indicators, and presence tracking. Messages are persisted
 * in a streaming store (NATS JetStream) while channel metadata and memberships are
 * stored in PostgreSQL. Extends [PermissionService] to enforce access control on
 * [ChatChannel] entities.
 */
interface ChatService : PermissionService<ChatChannel, UUID> {

    /**
     * Grants a permission on a chat channel to a security group.
     *
     * @param permission the permission to add, containing the entity ID, group ID, and action
     * @return the created [EntityPermission] record
     */
    suspend fun addPermission(permission: PermissionInput): EntityPermission

    /**
     * Revokes a permission on a chat channel from a security group.
     *
     * @param permission the permission to remove, containing the entity ID, group ID, and action
     * @return the removed [EntityPermission] record
     */
    suspend fun deletePermission(permission: PermissionInput): EntityPermission

    /**
     * Retrieves a chat channel by its unique identifier.
     *
     * @param id the unique identifier of the chat channel
     * @return the [ChatChannel] if found, or `null` if no channel exists with the given ID
     */
    suspend fun getById(id: UUID): ChatChannel?

    /**
     * Opens a member's reactive subscription to new messages posted in the specified channel. The
     * GraphQL boundary authorizes the subscription when it opens; after that, the durable channel
     * membership is the live-delivery entitlement. The flow deliberately does not continuously
     * re-evaluate the linked principal's channel ACL or an owning object's ACL. It completes when
     * the profile's membership is removed, or when hard deletion or administrative unlink cleanup
     * makes the profile unavailable. Soft deletion and later owning-object ACL changes do not
     * themselves end an existing flow because they do not remove membership.
     * The active connection suppresses redundant notifications for [profileId]. Public visibility
     * alone does not create membership or a real-time participation stream.
     *
     * @param channelId the unique identifier of the channel to subscribe to
     * @param profileId the authenticated profile receiving the messages
     * @return a [Flow] emitting [ChatMessage] instances as they are posted to the channel
     */
    fun subscribe(channelId: UUID, profileId: UUID): Flow<ChatMessage>

    /**
     * Opens a reactive subscription to typing indicator events in the specified channel. It uses
     * the same membership-scoped lifetime described by [subscribe].
     *
     * @param channelId the unique identifier of the channel to monitor
     * @param profileId the authenticated profile receiving the events
     * @return a [Flow] emitting [UserTypingEvent] instances when users start or stop typing
     */
    fun subscribeTyping(channelId: UUID, profileId: UUID): Flow<UserTypingEvent>

    /**
     * Opens a reactive subscription to presence status changes in the specified channel. It uses
     * the same membership-scoped lifetime described by [subscribe].
     *
     * @param channelId the unique identifier of the channel to monitor
     * @param profileId the authenticated profile receiving the events
     * @return a [Flow] emitting [PresenceUpdateEvent] instances when members change their presence status
     */
    fun subscribePresence(channelId: UUID, profileId: UUID): Flow<PresenceUpdateEvent>

    /**
     * Retrieves all chat channels that the specified profile is a member of.
     *
     * @param profileId the unique identifier of the user profile
     * @return a list of [ChatChannel] instances the profile belongs to
     */
    suspend fun getChannels(profileId: UUID): List<ChatChannel>

    /**
     * Retrieves all chat channels associated with a specific group identifier.
     *
     * @param groupId the unique identifier of the parent group
     * @return a list of [ChatChannel] instances belonging to the group
     */
    suspend fun getChannelsByGroupId(groupId: UUID): List<ChatChannel>

    /**
     * Creates a direct (one-to-one) chat channel between two user profiles. If a direct
     * channel already exists between the two profiles, implementations may return the existing channel.
     *
     * @param profileId1 the unique identifier of the first user profile
     * @param profileId2 the unique identifier of the second user profile
     * @return the newly created or existing [ChatChannel] of type [ChatChannelType.DIRECT]
     */
    suspend fun createDirectChannel(profileId1: UUID, profileId2: UUID): ChatChannel

    /**
     * Creates a new chat channel with the specified configuration.
     *
     * @param groupId the parent group to associate the channel with, or `null` for a standalone channel
     * @param name the display name for the channel
     * @param type the channel type (DIRECT, GROUP, or PUBLIC)
     * @param attributes optional JSON metadata to attach to the channel
     * @param dispatchCreatedEvent whether to dispatch the generic channel-created event immediately
     * @param initialMemberProfileId optional profile to add in the same transaction as the channel
     * @param initialMemberRole role assigned to [initialMemberProfileId]
     * @return the newly created [ChatChannel]
     */
    suspend fun createChannel(
        groupId: UUID?,
        name: String,
        type: ChatChannelType,
        attributes: JsonElement?,
        dispatchCreatedEvent: Boolean = true,
        initialMemberProfileId: UUID? = null,
        initialMemberRole: String = ChatChannelRoles.MEMBER,
    ): ChatChannel

    /**
     * Adds a user profile to a chat channel with the specified role when it is not already a member
     * and adds the profile's principal to the channel user group. Administrators are also added to
     * the channel administrator group.
     * Existing membership roles and attributes are never changed by this operation.
     * Direct-channel membership is immutable; direct channels must be created with exactly two
     * participants through [createDirectChannel].
     *
     * @param channelId the unique identifier of the channel to join
     * @param profileId the unique identifier of the user profile joining the channel
     * @param role the role to assign to the member within the channel (e.g., "admin", "member")
     * @param notifyExistingMembers whether a newly inserted membership should notify current members
     */
    suspend fun joinChannel(
        channelId: UUID,
        profileId: UUID,
        role: String,
        notifyExistingMembers: Boolean = true,
    )

    /**
     * Returns whether [profileId] is backed by an active principal that can participate in chat.
     */
    suspend fun canParticipate(profileId: UUID): Boolean

    /**
     * Changes an active existing channel member's role. Deleted and unlinked profiles cannot have
     * their roles changed. Callers authorize the operation through the channel's `MANAGE`
     * permission. Supported roles are `admin` and `member`.
     *
     * @param channelId the channel whose member role should be changed
     * @param profileId the existing member whose role should be changed
     * @param role the new canonical channel role
     */
    suspend fun setMemberRole(channelId: UUID, profileId: UUID, role: String)

    /**
     * Removes an existing member from a non-direct channel. Callers authorize the operation through
     * the channel's `MANAGE` permission.
     *
     * @param channelId the channel whose member should be removed
     * @param profileId the existing member to remove
     */
    suspend fun removeMember(channelId: UUID, profileId: UUID)

    /**
     * Removes a user profile from a chat channel. Its principal is removed from the channel's user
     * and administrator groups only when no other owned profile still requires those assignments.
     * Direct-channel membership is immutable and cannot be removed through this operation.
     *
     * @param channelId the unique identifier of the channel to leave
     * @param profileId the unique identifier of the user profile leaving the channel
     */
    suspend fun leaveChannel(channelId: UUID, profileId: UUID)

    /**
     * Retrieves all members of the specified chat channel.
     *
     * @param channelId the unique identifier of the channel
     * @return a list of [ChatChannelMember] instances representing the channel's members
     */
    suspend fun getMembers(channelId: UUID): List<ChatChannelMember>

    /** Returns [profileId]'s membership in [channelId], or null when the profile is not a member. */
    suspend fun getMember(channelId: UUID, profileId: UUID): ChatChannelMember? =
        getMembers(channelId).firstOrNull { it.profileId == profileId }

    /**
     * Retrieves memberships for the requested [profileIds] in [channelId].
     *
     * @return only profiles that are current channel members
     */
    suspend fun getMembers(channelId: UUID, profileIds: List<UUID>): List<ChatChannelMember>

    /**
     * Retrieves one offset-paginated page of channel members ordered by profile ID.
     *
     * @param channelId the channel whose members should be returned
     * @param offset the number of ordered members to skip
     * @param limit the maximum number of members to return
     */
    suspend fun getMembers(
        channelId: UUID,
        offset: Long,
        limit: Int,
    ): List<ChatChannelMember>

    /**
     * Broadcasts a typing indicator event to other members of the channel.
     *
     * @param channelId the unique identifier of the channel
     * @param profileId the unique identifier of the user profile that is typing
     * @param isTyping `true` if the user started typing, `false` if they stopped
     */
    suspend fun sendTyping(channelId: UUID, profileId: UUID, isTyping: Boolean)

    /**
     * Sends a new message to the specified chat channel.
     *
     * @param channelId the unique identifier of the target channel
     * @param senderId the unique identifier of the profile sending the message
     * @param clientId a client-generated UUID that must be reused when retrying the same logical
     * message; it deduplicates both storage and message-sent effects within the channel
     * @param content the list of [MessageContent] blocks composing the message body
     * @param attributes optional JSON metadata to attach to the message
     * @return the assigned sequence and whether this request duplicated an existing message
     */
    suspend fun sendMessage(
        channelId: UUID,
        senderId: UUID,
        clientId: UUID,
        content: List<MessageContent>,
        attributes: JsonElement? = null,
        parentSequence: Long? = null,
    ): ChatMessageSendResult

    /**
     * Retrieves one message from a channel by its stream sequence.
     *
     * @param channelId the unique identifier of the channel containing the message
     * @param sequence the stream sequence assigned when the message was sent
     * @return the message when the sequence belongs to [channelId], otherwise `null`
     */
    suspend fun getMessage(channelId: UUID, sequence: Long): ChatMessage?

    /**
     * Permanently deletes a message from the specified channel by its sequence number and emits a
     * live tombstone to channel subscribers. Caller authorization, including sender ownership, is
     * enforced at the API boundary.
     *
     * @param channelId the unique identifier of the channel containing the message
     * @param sequence the sequence number of the message to delete
     * @return `true` when the message was deleted, or `false` when the sequence does not identify a
     * message in [channelId]
     */
    suspend fun deleteMessage(channelId: UUID, sequence: Long): Boolean

    /**
     * Retrieves a paginated list of messages from the specified channel, optionally
     * filtered by sequence number boundaries for cursor-based pagination.
     *
     * @param channelId the unique identifier of the channel to retrieve messages from
     * @param before if specified, only returns messages with a sequence number less than this value
     * @param after if specified, only returns messages with a sequence number greater than this value
     * @param limit the maximum number of messages to return (defaults to 50)
     * @return a list of [ChatMessage] instances matching the criteria
     */
    suspend fun getMessages(
        channelId: UUID,
        before: Long? = null,
        after: Long? = null,
        limit: Int = 50
    ): List<ChatMessage>

    /**
     * Updates the last-read sequence number for a member in a channel, used to track
     * unread message counts and read receipts.
     *
     * @param channelId the unique identifier of the channel
     * @param profileId the unique identifier of the user profile
     * @param sequence the sequence number of the most recently read message
     */
    suspend fun updateLastRead(channelId: UUID, profileId: UUID, sequence: Long)

    /**
     * Retrieves the chat channel scoped to a specific domain object, if one exists.
     *
     * @param objectType the type of domain object
     * @param objectId the unique identifier of the domain object
     * @return the [ChatChannel] linked to the object, or `null` if none exists
     */
    suspend fun getByObject(objectType: ChatObjectType, objectId: UUID): ChatChannel?

    /**
     * Retrieves an existing object-scoped channel or creates one on first access.
     * The channel is created with [ChatChannelType.GROUP] visibility by default.
     *
     * @param objectType the type of domain object to scope the channel to
     * @param objectId the unique identifier of the domain object
     * @param name the display name for the channel if it needs to be created
     * @return the existing or newly created [ChatChannel]
     */
    suspend fun getOrCreateObjectChannel(objectType: ChatObjectType, objectId: UUID, name: String): ChatChannel

    /**
     * Adds an emoji reaction from a profile to a specific message.
     * Reactions are persisted in NATS KV (`chat-reactions` bucket).
     * Adding the same emoji twice is a no-op.
     */
    suspend fun addReaction(channelId: UUID, sequence: Long, profileId: UUID, emoji: String)

    /** Removes an emoji reaction from a profile on a specific message. */
    suspend fun removeReaction(channelId: UUID, sequence: Long, profileId: UUID, emoji: String)

    /**
     * Subscribes to reaction add/remove events on a channel so clients can update message reaction
     * counts in real time without re-fetching. The flow completes when [profileId] loses membership.
     */
    fun subscribeReactions(channelId: UUID, profileId: UUID): Flow<MessageReactionEvent>

    /**
     * Snapshot of who is currently typing in [channelId]. Lets a
     * freshly-connected client render the typing indicator
     * immediately without waiting for the next pub/sub event.
     */
    suspend fun getActiveTypers(channelId: UUID): List<UserTypingEvent>

    /** Records [profileId]'s presence as [status] (e.g. "online", "away"). */
    suspend fun setPresence(profileId: UUID, status: String)

    /** Returns the cached presence for [profileId], or null if unknown. */
    suspend fun getPresence(profileId: UUID): PresenceUpdateEvent?

    /**
     * Returns the last-read sequence for [profileId] in [channelId],
     * or null if the user has never recorded one.
     */
    suspend fun getLastRead(channelId: UUID, profileId: UUID): Long?
}
