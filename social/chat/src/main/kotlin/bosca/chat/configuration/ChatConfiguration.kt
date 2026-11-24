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

    private fun postgres(application: bosca.server.BoscaApplication): Boolean =
        application.environment.config.propertyOrNull("chat.storage")?.getString() == "postgres"

    @Provider(singleton = true)
    suspend fun chatMessageStore(application: bosca.server.BoscaApplication, nats: bosca.di.ObjectProvider<NatsConnectionPool>, pubsub: bosca.pubsub.PubSubService, json: kotlinx.serialization.json.Json): bosca.chat.message.ChatMessageStore =
        if (postgres(application)) bosca.chat.message.PostgresChatMessageStore(bosca.di.provide(), pubsub, json).also { store -> application.onShutdown { store.shutdown() } }
        else bosca.chat.message.NatsChatMessageStore(nats.get(), json)

    @Provider(singleton = true)
    suspend fun chatReactionStore(application: bosca.server.BoscaApplication, nats: bosca.di.ObjectProvider<NatsConnectionPool>): ChatReactionStore =
        if (postgres(application)) bosca.chat.state.PostgresChatReactionStore(bosca.di.provide()) else bosca.chat.state.NatsChatReactionStore(nats.get())

    @Provider(singleton = true)
    suspend fun chatTypingStore(application: bosca.server.BoscaApplication, nats: bosca.di.ObjectProvider<NatsConnectionPool>): ChatTypingStore =
        if (postgres(application)) bosca.chat.state.PostgresChatTypingStore(bosca.di.provide()) else bosca.chat.state.NatsChatTypingStore(nats.get())

    @Provider(singleton = true)
    suspend fun chatPresenceStore(application: bosca.server.BoscaApplication, nats: bosca.di.ObjectProvider<NatsConnectionPool>): ChatPresenceStore =
        if (postgres(application)) bosca.chat.state.PostgresChatPresenceStore(bosca.di.provide()) else bosca.chat.state.NatsChatPresenceStore(nats.get())

    @Provider(singleton = true)
    suspend fun chatReadStateStore(application: bosca.server.BoscaApplication, nats: bosca.di.ObjectProvider<NatsConnectionPool>): ChatReadStateStore =
        if (postgres(application)) bosca.chat.state.PostgresChatReadStateStore(bosca.di.provide()) else bosca.chat.state.NatsChatReadStateStore(nats.get())
}
