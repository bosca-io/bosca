package bosca.collaboration.configuration

import bosca.chat.service.ChatService
import bosca.collaboration.bridge.BridgeAdapterRegistry
import bosca.collaboration.bridge.BridgePlatformAdapter
import bosca.collaboration.bridge.BridgeService
import bosca.collaboration.bridge.slack.SlackAdapter
import bosca.collaboration.federation.FederationService
import bosca.collaboration.federation.LocalPeerIdProvider
import bosca.collaboration.repository.CollaborationMigration
import bosca.collaboration.service.ChatMessageDispatchListener
import bosca.collaboration.service.FederationInboundListener
import bosca.collaboration.service.MentionService
import bosca.configuration.service.ConfigurationService
import bosca.db.migrations.Migration
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

/**
 * Hand-wired bindings that the KSP-driven `@ServiceImplementation` graph
 * can't synthesize on its own. Three shapes show up here:
 *
 *  * Migration registration (Flyway needs to be told about the
 *    `collaboration` schema).
 *  * The Slack adapter, which needs an HttpClient + a per-binding
 *    bot-token resolver that lives on [BridgeService].
 *  * Concrete classes without a Service-extending interface — the
 *    KSP processor only emits `*ImplProvider` classes for impls of
 *    a `Service`-extending interface, so [LocalPeerIdProvider],
 *    [ChatMessageDispatchListener], and [FederationInboundListener]
 *    are wired here instead.
 *
 * The adapter registry takes the full list of platform adapters via the
 * DI container; only Slack ships today (Teams is intentionally absent
 * until the inbound webhook lands).
 */
@Providers
class CollaborationConfiguration {

    @Provider(name = "collaboration-migrations")
    fun migration(): Migration = CollaborationMigration()

    @Provider(singleton = true)
    fun slackAdapter(
        bridgeService: BridgeService,
        json: Json,
    ): SlackAdapter = SlackAdapter(
        httpClient = HttpClient(),
        json = json,
        tokenProvider = { binding ->
            bridgeService.getBotToken(binding.id) ?: error("No bot token configured for Slack binding ${binding.id}")
        },
    )

    @Provider(singleton = true)
    fun bridgeAdapterRegistry(slack: SlackAdapter): BridgeAdapterRegistry =
        BridgeAdapterRegistry(listOf<BridgePlatformAdapter>(slack))

    @Provider(singleton = true)
    fun localPeerIdProvider(
        configurationService: ConfigurationService,
    ): LocalPeerIdProvider = LocalPeerIdProvider(configurationService)

    @Provider(singleton = true)
    fun chatMessageDispatchListener(
        pubSubService: PubSubService,
        mentionService: MentionService,
        bridgeService: BridgeService,
        federationService: ObjectProvider<FederationService>,
        chatService: ObjectProvider<ChatService>,
        profileService: ObjectProvider<ProfileService>,
    ): ChatMessageDispatchListener = ChatMessageDispatchListener(
        pubSubService = pubSubService,
        mentionService = mentionService,
        bridgeService = bridgeService,
        federationService = federationService,
        chatService = chatService,
        profileService = profileService,
    )

    @Provider(singleton = true)
    fun federationInboundListener(
        pubSubService: PubSubService,
        federationServiceProvider: ObjectProvider<FederationService>,
        chatService: ObjectProvider<ChatService>,
        profileService: ObjectProvider<ProfileService>,
    ): FederationInboundListener = FederationInboundListener(
        pubSubService = pubSubService,
        federationServiceProvider = federationServiceProvider,
        chatService = chatService,
        profileService = profileService,
    )
}
