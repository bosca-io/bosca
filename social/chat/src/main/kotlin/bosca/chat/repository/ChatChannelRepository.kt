package bosca.chat.repository

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatObjectType
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Provides data access operations for chat channels and their membership
 * records, stored in the `chat` PostgreSQL schema.
 */
@Repository
interface ChatChannelRepository {

    @Query("select cc.* from chat.channels cc join chat.channel_members ccm on cc.id = ccm.channel_id where ccm.profile_id = :profileId")
    suspend fun getChannelsByProfileId(profileId: UUID): List<ChatChannel>

    @Query("select * from chat.channels where id = :id")
    suspend fun getById(id: UUID): ChatChannel?

    @Query("select * from chat.channels where id = :id for update")
    suspend fun getByIdForUpdate(id: UUID): ChatChannel?

    @Query("select * from chat.channels where group_id = :groupId")
    suspend fun getChannelsByGroupId(groupId: UUID): List<ChatChannel>

    @Query("insert into chat.channels (group_id, name, type, attributes) values (:groupId, :name, :type::chat_channel_type, :attributes) returning *")
    suspend fun createChannel(groupId: UUID?, name: String, type: ChatChannelType, attributes: JsonElement?): ChatChannel

    /** Creates the direct channel with its deterministic pair-derived [id], or returns null when it exists. */
    @Query(
        "insert into chat.channels " +
            "(id, group_id, name, type, attributes) " +
            "values (:id, null, 'DM', 'direct', null) " +
            "on conflict (id) " +
            "do nothing returning *"
    )
    suspend fun createDirectChannelIfAbsent(id: UUID): ChatChannel?

    // The legacy `last_read_at` and `last_read_sequence` columns still
    // exist on the table from V76 of the core migrations but are now
    // unused — last-read state moved to the `chat-read-state` NATS KV
    // bucket. Inserts leave the columns null; readers don't project
    // them. A future cleanup migration can drop the columns.
    @Query(
        "insert into chat.channel_members (channel_id, profile_id, role, attributes) " +
            "values (:channelId, :profileId, :role, :attributes) " +
            "on conflict (channel_id, profile_id) do nothing",
        returnUpdateCount = true,
    )
    suspend fun addMemberIfAbsent(
        channelId: UUID,
        profileId: UUID,
        role: String,
        attributes: JsonElement? = null,
    ): Int

    @Query("select channel_id, profile_id, role, attributes from chat.channel_members where channel_id = :channelId and profile_id = :profileId")
    suspend fun getMember(channelId: UUID, profileId: UUID): ChatChannelMember?

    @Query(
        "update chat.channel_members set role = :role " +
            "where channel_id = :channelId and profile_id = :profileId and role <> :role",
        returnUpdateCount = true,
    )
    suspend fun updateMemberRole(channelId: UUID, profileId: UUID, role: String): Int

    @Query(
        "delete from chat.channel_members where channel_id = :channelId and profile_id = :profileId",
        returnUpdateCount = true,
    )
    suspend fun removeMember(channelId: UUID, profileId: UUID): Int

    @Query("select channel_id, profile_id, role, attributes from chat.channel_members where channel_id = :channelId")
    suspend fun getMembers(channelId: UUID): List<ChatChannelMember>

    @Query("select channel_id, profile_id, role, attributes from chat.channel_members where profile_id = :profileId")
    suspend fun getMembershipsByProfileId(profileId: UUID): List<ChatChannelMember>

    /**
     * Returns whether another active profile owned by [principalId] still requires the channel
     * group represented by [role]. A null role checks any channel membership.
     */
    @Query(
        "select exists (" +
            "select 1 from chat.channel_members cm " +
            "join public.profiles p on p.id = cm.profile_id " +
            "where cm.channel_id = :channelId and p.principal = :principalId " +
            "and p.deleted_at is null and cm.profile_id <> :excludedProfileId " +
            "and (:role is null or cm.role = :role)" +
            ")"
    )
    suspend fun hasOtherMemberForPrincipal(
        channelId: UUID,
        principalId: UUID,
        excludedProfileId: UUID,
        role: String?,
    ): Boolean

    @Query(
        "select channel_id, profile_id, role, attributes from chat.channel_members " +
            "where channel_id = :channelId and profile_id = any(:profileIds)"
    )
    suspend fun getMembers(channelId: UUID, profileIds: List<UUID>): List<ChatChannelMember>

    @Query(
        "select channel_id, profile_id, role, attributes from chat.channel_members " +
            "where channel_id = :channelId order by profile_id offset :offset limit :limit"
    )
    suspend fun getMembers(channelId: UUID, offset: Long, limit: Int): List<ChatChannelMember>

    @Query("select * from chat.channels where object_type = :objectType::chat.object_type and object_id = :objectId")
    suspend fun getByObject(objectType: ChatObjectType, objectId: UUID): ChatChannel?

    /** Creates an object-scoped channel, or returns null when one already exists for the object. */
    @Query(
        "insert into chat.channels (group_id, name, type, attributes, object_type, object_id) " +
            "values (null, :name, 'group', null, :objectType::chat.object_type, :objectId) " +
            "on conflict (object_type, object_id) where object_type is not null and object_id is not null " +
            "do nothing returning *"
    )
    suspend fun createObjectChannelIfAbsent(name: String, objectType: ChatObjectType, objectId: UUID): ChatChannel?

    /** Returns direct channels whose membership cascade left them without exactly two participants. */
    @Query(
        "select channel.* from chat.channels channel " +
            "where channel.type = 'direct' and (" +
            "select count(*) from chat.channel_members member where member.channel_id = channel.id" +
            ") <> 2"
    )
    suspend fun getIncompleteDirectChannels(): List<ChatChannel>

    /** Deletes a channel and its relational memberships, permissions, and invitations. */
    @Query("delete from chat.channels where id = :id", returnUpdateCount = true)
    suspend fun deleteChannel(id: UUID): Int
}
