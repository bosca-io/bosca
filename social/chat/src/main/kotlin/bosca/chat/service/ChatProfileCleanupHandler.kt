package bosca.chat.service

import bosca.chat.events.ChatChannelMemberRemovedEvent
import bosca.chat.events.ChatProfileUnavailableEvent
import bosca.chat.events.dispatch
import bosca.chat.model.ChatChannelRoles
import bosca.chat.model.ChatChannelType
import bosca.chat.repository.ChatChannelInvitationRepository
import bosca.chat.repository.ChatChannelRepository
import bosca.chat.state.ChatPresenceStore
import bosca.chat.state.ChatReactionStore
import bosca.chat.state.ChatReadStateStore
import bosca.chat.state.ChatTypingStore
import bosca.db.transaction
import bosca.profile.profile.service.ProfileCleanupHandler
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import bosca.serialization.UUID

/** Removes chat participation after a profile is hard-deleted or administratively unlinked. */
class ChatProfileCleanupHandler(
    private val channelRepository: ChatChannelRepository,
    private val invitationRepository: ChatChannelInvitationRepository,
    private val securityService: SecurityService,
    private val reactionStore: ChatReactionStore,
    private val readStateStore: ChatReadStateStore,
    private val typingStore: ChatTypingStore,
    private val presenceStore: ChatPresenceStore,
) : ProfileCleanupHandler {

    override suspend fun onProfileCleanup(profileId: UUID, principalId: UUID?) {
        // A hard delete has already cascaded relational memberships. An administrative unlink has
        // not, so capture and remove those memberships here. Either path can leave a direct channel
        // without a usable participant, and a direct channel is deleted as a unit.
        val profileChannels = channelRepository.getChannelsByProfileId(profileId)
        val profileMemberships = channelRepository.getMembershipsByProfileId(profileId)
        val directChannels = (profileChannels.filter { it.type == ChatChannelType.DIRECT } +
            channelRepository.getIncompleteDirectChannels()).associateBy { it.id }.values
        val directChannelIds = directChannels.mapTo(mutableSetOf()) { it.id }
        val remainingMembers = directChannels.associate { channel ->
            channel.id to channelRepository.getMembers(channel.id)
        }

        for (channel in directChannels) {
            reactionStore.clearChannel(channel.id)
            readStateStore.clearChannel(channel.id)
            typingStore.clearChannel(channel.id)
        }
        reactionStore.clearProfile(profileId)
        readStateStore.clearProfile(profileId)
        typingStore.clearProfile(profileId)
        presenceStore.clearPresence(profileId)

        val principalChannelGroups = principalId?.let { securityService.getPrincipalGroups(it) }.orEmpty()
            .filter { ChatChannelSecurityGroups.channelId(it.name) != null }

        transaction {
            invitationRepository.cancelPendingByProfile(profileId)

            for (membership in profileMemberships) {
                if (membership.channelId in directChannelIds) continue
                if (channelRepository.removeMember(membership.channelId, profileId) > 0) {
                    ChatChannelMemberRemovedEvent(membership.channelId, profileId).dispatch()
                }
            }

            for (channel in directChannels) {
                val removed = channelRepository.deleteChannel(channel.id) > 0
                deleteChannelGroup(ChatChannelSecurityGroups.usersName(channel.id))
                deleteChannelGroup(ChatChannelSecurityGroups.administratorsName(channel.id))
                if (removed) {
                    remainingMembers.getValue(channel.id).forEach { member ->
                        ChatChannelMemberRemovedEvent(channel.id, member.profileId).dispatch()
                    }
                }
            }

            if (principalId != null) {
                for (group in principalChannelGroups) {
                    val channelId = checkNotNull(ChatChannelSecurityGroups.channelId(group.name))
                    val role = if (group.name == ChatChannelSecurityGroups.administratorsName(channelId)) {
                        ChatChannelRoles.ADMIN
                    } else {
                        null
                    }
                    if (channelId !in directChannelIds &&
                        !channelRepository.hasOtherMemberForPrincipal(channelId, principalId, profileId, role)
                    ) {
                        securityService.removePrincipalGroup(principalId, group.id)
                    }
                }
            }

            ChatProfileUnavailableEvent(profileId).dispatch()
        }
    }

    private suspend fun deleteChannelGroup(name: String) {
        securityService.getGroupByName(name, GroupType.SYSTEM)?.let { securityService.deleteGroup(it.id) }
    }
}
