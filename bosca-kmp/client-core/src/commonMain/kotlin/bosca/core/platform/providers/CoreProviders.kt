package bosca.core.platform.providers

import bosca.core.analytics.InstallationIdProvider
import bosca.core.analytics.AnalyticsSessionIdProvider
import bosca.core.devices.DeviceRegistrationManager
import bosca.core.devices.DeviceRegistrationManagerImpl
import bosca.core.notifications.DeviceRegistrationGateway
import bosca.core.notifications.GraphQLDeviceRegistrationGateway
import bosca.core.notifications.GraphQLNotificationTypeGateway
import bosca.core.notifications.NotificationTypeGateway
import bosca.core.notifications.NotificationTypeManager
import bosca.core.notifications.NotificationTypeManagerImpl
import bosca.core.notifications.PushDevicePlatform
import bosca.core.notifications.PushRegistrationManager
import bosca.core.notifications.PushRegistrationManagerImpl
import bosca.core.notifications.PushTokenProvider
import bosca.core.platform.CurrentPlatformType
import bosca.core.platform.Log
import bosca.core.platform.PlatformType
import bosca.core.preferences.Preferences
import bosca.core.security.AuthGraphql
import bosca.core.security.AuthGraphqlImpl
import bosca.core.security.AuthHttpClient
import bosca.core.security.BoscaAuth
import bosca.core.security.BoscaAuthImpl
import bosca.core.security.IdentityStorage
import bosca.core.security.TokenStorage
import bosca.core.security.model.BoscaAuthConfig
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.graphql.client.BoscaGraphQLClientInfo
import bosca.graphql.client.GraphQLClient
import bosca.graphql.client.GraphQLRequestInstrumentation
import bosca.graphql.client.GraphQLSubscriptionClient
import bosca.graphql.client.KtorGraphQLClient
import bosca.graphql.client.KtorGraphQLSubscriptionClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

private const val GRAPHQL_INSTALLATION_ID_TIMEOUT_MILLIS = 3_000L

@Providers
class CoreProviders {

    /**
     * Dedicated HTTP client for the auth layer (io.bosca:auth-shared). It hand-rolls the auth GraphQL calls
     * over Ktor and attaches bearer tokens per call, so refresh/login never recurse through the main client's
     * auto-refreshing [headerProvider].
     */
    @Provider(singleton = true)
    fun provideAuthHttpClient(config: BoscaAuthConfig): AuthHttpClient = AuthHttpClient(config)

    @Provider
    fun provideAuthGraphql(authHttpClient: AuthHttpClient): AuthGraphql = AuthGraphqlImpl(authHttpClient)

    @Provider
    fun provideBoscaAuthConfig(urls: Urls): BoscaAuthConfig =
        BoscaAuthConfig(apiUrl = urls.api, graphqlUrl = urls.graphql)

    @Provider(singleton = true)
    fun provideBoscaAuth(
        tokenStorage: TokenStorage,
        identityStorage: IdentityStorage,
        graphql: AuthGraphql,
        config: BoscaAuthConfig,
    ): BoscaAuth = BoscaAuthImpl(
        storage = tokenStorage,
        identityStorage = identityStorage,
        graphql = graphql,
        config = config,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** GraphQL boundary shared by device registration and push-token registration. */
    @Provider(singleton = true)
    fun provideDeviceRegistrationGateway(client: GraphQLClient): DeviceRegistrationGateway =
        GraphQLDeviceRegistrationGateway(client)

    /** Public server notification-type catalog used to configure native presentation channels. */
    @Provider(singleton = true)
    fun provideNotificationTypeGateway(client: GraphQLClient): NotificationTypeGateway =
        GraphQLNotificationTypeGateway(client)

    /** Synchronizes the server notification-type catalog with the current native platform. */
    @Provider(singleton = true)
    fun provideNotificationTypeManager(
        gateway: NotificationTypeGateway,
        tokenProvider: PushTokenProvider,
    ): NotificationTypeManager = NotificationTypeManagerImpl(gateway, tokenProvider)

    /** App-installation registration and authenticated-principal association lifecycle. */
    @Provider(singleton = true)
    fun provideDeviceRegistrationManager(
        preferences: Preferences,
        gateway: DeviceRegistrationGateway,
        installationIdProvider: InstallationIdProvider,
    ): DeviceRegistrationManager = DeviceRegistrationManagerImpl(
        preferences = preferences,
        gateway = gateway,
        installationIdProvider = installationIdProvider,
        platform = CurrentPlatformType.toPushDevicePlatform(),
    )

    /** Remote-push token registration and rotation lifecycle. */
    @Provider(singleton = true)
    fun providePushRegistrationManager(
        preferences: Preferences,
        gateway: DeviceRegistrationGateway,
        tokenProvider: PushTokenProvider,
        deviceRegistration: DeviceRegistrationManager,
        notificationTypes: NotificationTypeManager,
    ): PushRegistrationManager = PushRegistrationManagerImpl(
        preferences = preferences,
        gateway = gateway,
        tokenProvider = tokenProvider,
        deviceRegistration = deviceRegistration,
        notificationTypes = notificationTypes,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /**
     * Main app GraphQL client over io.bosca:bosca-graphql-client (replaces Apollo). The [headerProvider] pulls
     * a *valid* (auto-refreshing) bearer token from [BoscaAuth] per request. [KtorGraphQLClient] receives the
     * application and installation identity separately and owns emission of the standard Bosca headers. Depends
     * on [BoscaAuth] (which depends only on the auth client), so there is no construction cycle. Scalars are
     * handled by the generated operations' kotlinx serializers, so no scalar adapters are registered.
     * (Subscriptions ride a separate streaming transport.)
     */
    @Provider(singleton = true)
    fun provideGraphQLRequestInstrumentation(): GraphQLRequestInstrumentation = GraphQLRequestInstrumentation()

    @Provider(singleton = true)
    fun provideGraphQL(
        urls: Urls,
        auth: BoscaAuth,
        instrumentation: GraphQLRequestInstrumentation,
        clientConfig: ObjectProvider<BoscaClientConfig>,
        installationIds: ObjectProvider<InstallationIdProvider>,
        sessionIds: ObjectProvider<AnalyticsSessionIdProvider>,
    ): GraphQLClient =
        KtorGraphQLClient(
            endpoint = urls.graphql,
            httpClient = HttpClient(),
            headerProvider = {
                val token = try {
                    auth.getToken()
                } catch (e: Exception) {
                    Log.e("Failed to fetch auth token for GraphQL request", e)
                    null
                }
                if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
            },
            instrumentation = instrumentation,
            boscaInfoProvider = boscaGraphQLClientInfoProvider(clientConfig, installationIds, sessionIds),
        )

    /**
     * Real-time subscriptions over a `graphql-transport-ws` websocket (replaces Apollo's
     * `WebSocketNetworkTransport`). `connection_init` carries an auto-refreshing bearer token — the same auth
     * the main client attaches per request.
     */
    @Provider(singleton = true)
    fun provideGraphQLSubscriptions(urls: Urls, auth: BoscaAuth): GraphQLSubscriptionClient =
        KtorGraphQLSubscriptionClient(
            endpoint = urls.graphqlWs,
            httpClient = HttpClient { install(WebSockets) },
            connectionPayloadProvider = {
                val token = try {
                    auth.getToken()
                } catch (e: Exception) {
                    Log.e("Failed to fetch auth token for GraphQL subscription", e)
                    null
                }
                if (token != null) buildJsonObject { put("Authorization", "Bearer $token") } else null
            },
        )
}

internal fun boscaGraphQLClientInfoProvider(
    clientConfig: ObjectProvider<BoscaClientConfig>,
    installationIds: ObjectProvider<InstallationIdProvider>,
    sessionIds: ObjectProvider<AnalyticsSessionIdProvider>? = null,
): suspend () -> BoscaGraphQLClientInfo? = {
    if (clientConfig.exists && installationIds.exists) {
        val config = clientConfig.get()
        val installationId = try {
            withTimeoutOrNull(GRAPHQL_INSTALLATION_ID_TIMEOUT_MILLIS.milliseconds) {
                installationIds.get().getOrCreate()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Failed to get the installation ID for a GraphQL request; a later request will retry", e)
            null
        }
        if (installationId == null) {
            null
        } else {
            BoscaGraphQLClientInfo(
                installationId = installationId,
                appId = config.appId,
                appVersion = config.appVersion,
                sessionId = sessionIds?.takeIf { it.exists }?.get()?.sessionId(),
            )
        }
    } else {
        null
    }
}

private fun PlatformType.toPushDevicePlatform(): PushDevicePlatform = when (this) {
    PlatformType.Desktop -> PushDevicePlatform.DESKTOP
    PlatformType.WebJs,
    PlatformType.WebWasm -> PushDevicePlatform.WEB
    PlatformType.Android -> PushDevicePlatform.ANDROID
    PlatformType.Ios -> PushDevicePlatform.IOS
}
