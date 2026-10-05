package bosca.chat.configuration

import bosca.chat.repository.ChatChannelInvitationRepository
import bosca.chat.repository.ChatChannelRepository
import bosca.chat.repository.ChatMigration
import bosca.chat.security.ChatChannelPermissionEvaluator
import bosca.chat.security.ChatObjectPermissionEvaluator
import bosca.chat.service.ChatProfileCleanupHandler
import bosca.chat.service.ChatService
import bosca.chat.state.ChatPresenceStore
import bosca.chat.state.ChatReactionStore
import bosca.chat.state.ChatReadStateStore
import bosca.chat.state.ChatTypingStore
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.nats.NatsConnectionPool
import bosca.profile.profile.service.ProfileCleanupHandler
import bosca.profile.profile.service.ProfileService
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService

@Providers
class ChatConfiguration {

    @Provider(name = "chat-migrations")
    fun migration(): Migration = ChatMigration()

    @Provider(singleton = true)
    fun chatChannelPermissionEvaluator(
        service: ChatService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = ChatChannelPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun chatObjectPermissionEvaluator(
        metadataService: MetadataService,
        metadataPermissionEvaluator: MetadataPermissionEvaluator,
        collectionService: CollectionService,
        collectionPermissionEvaluator: CollectionPermissionEvaluator,
    ) = ChatObjectPermissionEvaluator(
        metadataService,
        metadataPermissionEvaluator,
        collectionService,
        collectionPermissionEvaluator,
    )

    /** Removes chat participation after a profile is hard-deleted or administratively unlinked. */
    @Provider(name = "chat-profile-cleanup-handler")
    fun chatProfileCleanupHandler(
        channelRepository: ChatChannelRepository,
        invitationRepository: ChatChannelInvitationRepository,
        securityService: SecurityService,
        reactionStore: ChatReactionStore,
        readStateStore: ChatReadStateStore,
        typingStore: ChatTypingStore,
        presenceStore: ChatPresenceStore,
    ): ProfileCleanupHandler = ChatProfileCleanupHandler(
        channelRepository,
        invitationRepository,
        securityService,
        reactionStore,
        readStateStore,
        typingStore,
        presenceStore,
    )

    /**
     * KV-backed reaction store. Singleton because each instance lazily
     * caches the [io.nats.client.KeyValue] handle for the bucket; many
     * instances would each open their own and waste connection-level
     * caching on the NATS client.
     */
    @Provider(singleton = true)
    fun chatReactionStore(nats: NatsConnectionPool) = ChatReactionStore(nats)

    @Provider(singleton = true)
    fun chatTypingStore(nats: NatsConnectionPool) = ChatTypingStore(nats)

    @Provider(singleton = true)
    fun chatPresenceStore(nats: NatsConnectionPool) = ChatPresenceStore(nats)

    @Provider(singleton = true)
    fun chatReadStateStore(nats: NatsConnectionPool) = ChatReadStateStore(nats)
}
