package bosca.server

import bosca.ai.configuration.AIModule
import bosca.analytics.service.NatsEventConsumer
import bosca.analytics.server.AnalyticsServerClientModule
import bosca.cache.withRequestCache
import bosca.configuration.GraphQLModule
import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.db.withConnectionManager
import bosca.di.AIProviderRegistrar
import bosca.di.ArtifactsAdminProviderRegistrar
import bosca.di.ArtifactsProviderRegistrar
import bosca.di.CoreArtifactsProviderRegistrar
import bosca.di.CalendarProviderRegistrar
import bosca.di.AnalyticsAIProviderRegistrar
import bosca.di.AnalyticsProviderRegistrar
import bosca.di.BackupProviderRegistrar
import bosca.di.BoscaProviderRegistrar
import bosca.di.CommentsProviderRegistrar
import bosca.di.CommunityProviderRegistrar
import bosca.di.ConfigurationProviderRegistrar
import bosca.di.ContentProviderRegistrar
import bosca.di.CoreAnalyticsProviderRegistrar
import bosca.di.CoreCommunityProviderRegistrar
import bosca.di.CoreConfigurationProviderRegistrar
import bosca.di.CoreContentProviderRegistrar
import bosca.di.CoreCalendarProviderRegistrar
import bosca.di.CoreKubernetesProviderRegistrar
import bosca.di.KubernetesPipelinesProviderRegistrar
import bosca.di.CoreGatewayProviderRegistrar
import bosca.di.CoreWorkOpsProviderRegistrar
import bosca.di.KubernetesProviderRegistrar
import bosca.di.CoreDevicesProviderRegistrar
import bosca.di.CoreExperimentationProviderRegistrar
import bosca.di.CoreFormsProviderRegistrar
import bosca.di.CoreLanguagesProviderRegistrar
import bosca.di.CoreLocalizationProviderRegistrar
import bosca.di.CoreCommunicationsProviderRegistrar
import bosca.di.CoreProfileProviderRegistrar
import bosca.di.CoreSchedulerProviderRegistrar
import bosca.di.CoreEventsProviderRegistrar
import bosca.di.CorePipelinesProviderRegistrar
import bosca.di.CoreEcommerceProviderRegistrar
import bosca.di.CoreFeedsProviderRegistrar
import bosca.di.CoreScriptingProviderRegistrar
import bosca.di.CoreSearchProviderRegistrar
import bosca.di.CoreSecurityProviderRegistrar
import bosca.di.ChatProviderRegistrar
import bosca.di.CollaborationProviderRegistrar
import bosca.di.CoreChatProviderRegistrar
import bosca.di.CoreCollaborationProviderRegistrar
import bosca.di.CoreGitCiProviderRegistrar
import bosca.di.CoreGitProviderRegistrar
import bosca.di.CoreRecommendationsProviderRegistrar
import bosca.di.CoreSegmentationProviderRegistrar
import bosca.di.CoreStorageProviderRegistrar
import bosca.di.DevicesProviderRegistrar
import bosca.di.DiagnosticsProviderRegistrar
import bosca.di.DocsProviderRegistrar
import bosca.di.ExperimentationProviderRegistrar
import bosca.di.FormsProviderRegistrar
import bosca.di.GatewayProviderRegistrar
import bosca.di.GitCiProviderRegistrar
import bosca.di.GitJobsProviderRegistrar
import bosca.di.GitProviderRegistrar
import bosca.di.LocalizationProviderRegistrar
import bosca.di.HubSpotProviderRegistrar
import bosca.di.KitProviderRegistrar
import bosca.di.LanguagesProviderRegistrar
import bosca.di.MeilisearchAdminProviderRegistrar
import bosca.di.MessagesPagesProviderRegistrar
import bosca.di.CommunicationsProviderRegistrar
import bosca.di.MuxProviderRegistrar
import bosca.di.NatsAdminProviderRegistrar
import bosca.di.PostgresAdminProviderRegistrar
import bosca.di.ProfileProviderRegistrar
import bosca.di.SchedulerProviderRegistrar
import bosca.di.EventsProviderRegistrar
import bosca.di.PipelinesProviderRegistrar
import bosca.di.EcommerceProviderRegistrar
import bosca.di.FeedsProviderRegistrar
import bosca.di.ScriptingProviderRegistrar
import bosca.di.SearchProviderRegistrar
import bosca.di.SecurityProviderRegistrar
import bosca.di.RecommendationsProviderRegistrar
import bosca.di.SegmentationProviderRegistrar
import bosca.di.SlugProviderRegistrar
import bosca.di.StorageProviderRegistrar
import bosca.di.StorePipelinesProviderRegistrar
import bosca.di.WorkOpsProviderRegistrar
import bosca.di.provide
import bosca.di.providerMissing
import bosca.di.provides
import bosca.ecommerce.configuration.JobQueueNames
import bosca.features.Features
import bosca.http.StatusPageModule
import bosca.hubspot.client.HubSpot
import bosca.hubspot.configuration.HubSpotConfiguration
import bosca.initialization.InitializeModule
import bosca.installer.model.PackageInstallations
import bosca.pipelines.configuration.PipelinesModule
import bosca.routes.configureCollaborationRoutes
import bosca.routes.configureCommunicationsRoutes
import bosca.routes.configureContentRoutes
import bosca.routes.configureGatewayRoutes
import bosca.routes.configureMessagesPagesPageRoutes
import bosca.routes.configurePipelinesRoutes
import bosca.routes.configureProfileRoutes
import bosca.routes.configureScriptingRoutes
import bosca.scheduler.configuration.SchedulerConfiguration
import bosca.scheduler.listeners.JobEnqueueEventForwarder
import bosca.scheduler.runner.SchedulerRunner
import bosca.security.routes.SecurityRoutesModule
import bosca.server.graphql.BoscaGraphQLService
import bosca.server.netty.NettyServerEngine
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.configuration.RegisterJobsConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

fun main(args: Array<String>) {
    NettyServerEngine.start("application.yaml", args) { module() }
}

@Serializable
data class RunnerConfig(
    val enabled: List<String> = emptyList()
)

@OptIn(ExperimentalUuidApi::class)
suspend fun BoscaApplication.module() {
    // Module installation order matters:
    // 1. InitializeModule — sets up database connections, DI providers, and caches
    //    that all subsequent modules depend on
    // 2. AnalyticsServerClientModule — installs the in-process error capture
    //    middleware (replaces the deleted SentryModule). Must be installed after
    //    the analytics provider registrar runs so EventProcessingService is available.
    // 3. SecurityRoutesModule — authentication middleware must be registered before
    //    route modules that declare auth-protected endpoints
    // 4. GraphQLModule, AIModule — route modules
    //    (order among these is not significant)
    // 5. StatusPageModule — registered last so its error handlers can catch exceptions
    //    from all preceding modules

    val registrars = mutableListOf(
        BoscaProviderRegistrar(),

        // Core providers
        CoreContentProviderRegistrar(),
        CoreProfileProviderRegistrar(),
        CoreSecurityProviderRegistrar(),
        CoreCommunicationsProviderRegistrar(),
        CoreLanguagesProviderRegistrar(),
        CoreAnalyticsProviderRegistrar(),
        CoreConfigurationProviderRegistrar(),
        CoreSearchProviderRegistrar(),
        CoreStorageProviderRegistrar(),
        CoreSchedulerProviderRegistrar(),
        CoreEventsProviderRegistrar(),
        CorePipelinesProviderRegistrar(),
        CoreFeedsProviderRegistrar(),
        CoreScriptingProviderRegistrar(),
        CoreFormsProviderRegistrar(),
        CoreLocalizationProviderRegistrar(),
        CoreSegmentationProviderRegistrar(),
        CoreRecommendationsProviderRegistrar(),
        CoreExperimentationProviderRegistrar(),
        CoreDevicesProviderRegistrar(),
        CoreCalendarProviderRegistrar(),

        // Providers
        ProfileProviderRegistrar(),
        SecurityProviderRegistrar(),
        SlugProviderRegistrar(),
        ContentProviderRegistrar(),
        CommunicationsProviderRegistrar(),
        MessagesPagesProviderRegistrar(),
        SearchProviderRegistrar(),
        LanguagesProviderRegistrar(),
        AnalyticsProviderRegistrar(),
        ConfigurationProviderRegistrar(),
        SchedulerProviderRegistrar(),
        EventsProviderRegistrar(),
        PipelinesProviderRegistrar(),
        FeedsProviderRegistrar(),
        AIProviderRegistrar(),
        KitProviderRegistrar(),
        StorageProviderRegistrar(),
        HubSpotProviderRegistrar(),
        MuxProviderRegistrar(),
        DiagnosticsProviderRegistrar(),
        DocsProviderRegistrar(),
        ScriptingProviderRegistrar(),
        BackupProviderRegistrar(),
        FormsProviderRegistrar(),
        LocalizationProviderRegistrar(),
        SegmentationProviderRegistrar(),
        RecommendationsProviderRegistrar(),
        ExperimentationProviderRegistrar(),
        DevicesProviderRegistrar(),
        CalendarProviderRegistrar(),
        MeilisearchAdminProviderRegistrar(),
        NatsAdminProviderRegistrar(),
        PostgresAdminProviderRegistrar(),
        CoreArtifactsProviderRegistrar(),
        ArtifactsProviderRegistrar(),
        ArtifactsAdminProviderRegistrar(),

        // AI
        AnalyticsAIProviderRegistrar()
    )

    // Comments live in the content domain (content/comments); Metadata.comments
    // references their GraphQL types, so the registrar loads unconditionally.
    registrars.add(CommentsProviderRegistrar())

    if (Features.community) {
        registrars.add(CoreCommunityProviderRegistrar())
        registrars.add(CommunityProviderRegistrar())
    }

    registrars.add(CoreGitProviderRegistrar())
    registrars.add(CoreGitCiProviderRegistrar())
    registrars.add(GitProviderRegistrar())
    registrars.add(GitCiProviderRegistrar())
    registrars.add(GitJobsProviderRegistrar())

    if (Features.chat) {
        // Chat (JetStream-backed message store) and collaboration (NATS pub/sub
        // for federation listener + dispatch event bus) both require NATS in
        // the cluster. Gate them together so a deployment without NATS can opt
        // the whole family out via `chat.enabled: false`.
        registrars.add(CoreChatProviderRegistrar())
        registrars.add(CoreCollaborationProviderRegistrar())
        registrars.add(ChatProviderRegistrar())
        registrars.add(CollaborationProviderRegistrar())
    }

    if (Features.workops) {
        registrars.add(CoreWorkOpsProviderRegistrar())
        registrars.add(WorkOpsProviderRegistrar())
        registrars.add(StorePipelinesProviderRegistrar())
        // Native release actions use the Kubernetes deploy adapters.
        // The KubernetesControllerClient dependency resolves lazily, so only an actual Helm deploy
        // needs the kubernetes feature to be enabled.
        registrars.add(KubernetesPipelinesProviderRegistrar())
    }

    if (Features.ecommerce) {
        registrars.add(CoreEcommerceProviderRegistrar())
        registrars.add(EcommerceProviderRegistrar())
    }

    if (Features.gateway) {
        registrars.add(CoreGatewayProviderRegistrar())
        registrars.add(GatewayProviderRegistrar())
    }

    if (Features.kubernetes) {
        registrars.add(CoreKubernetesProviderRegistrar())
        registrars.add(KubernetesProviderRegistrar())
    }

    // Resolved by the scriptingEnabled/scriptingDisabled source-set variant
    // selected at build time via -Pbosca.scripting.engine. Returns null when
    // the scripting-engine module is excluded; the engine() DI provider then
    // falls back to RemoteEngine, and execution is delegated to a worker via
    // RemoteScriptExecutionServiceImpl.
    ScriptingEngineRegistrarProvider.get()?.let { registrars.add(it) }

    log.info("enabled providers: ${registrars.joinToString { it.javaClass.simpleName }}")
    install(InitializeModule(providers = registrars.toTypedArray()))

    log.info("configuring analytics listeners")
    install(bosca.analytics.AnalyticsModule())

    log.info("configuring scripting listeners")
    install(bosca.scripting.ScriptingModule())

    if (Features.kubernetes) {
        log.info("configuring kubernetes listeners")
        install(bosca.kubernetes.KubernetesModule())
    }

    log.info("configuring in-process analytics error capture")
    install(AnalyticsServerClientModule())

    log.info("configuring security routes")
    install(SecurityRoutesModule())

    log.info("configuring graphql")
    val graphql = BoscaGraphQLService(Features.introspection)
    install(GraphQLModule(graphql))

    log.info("configuring koog routes")
    install(AIModule())

    log.info("configuring pipelines git sync")
    install(PipelinesModule())

    log.info("configuring content routes")
    configureContentRoutes()

    log.info("configuring profile routes")
    configureProfileRoutes()

    log.info("configuring scripting routes")
    configureScriptingRoutes()

    log.info("configuring pipeline routes")
    configurePipelinesRoutes()

    log.info("configuring message routes")
    routing {
        staticResources("/", "static")
    }
    configureMessagesPagesPageRoutes()

    log.info("configuring communications routes")
    configureCommunicationsRoutes()

    if (Features.chat) {
        log.info("configuring collaboration routes")
        configureCollaborationRoutes()
    }

    if (Features.gateway) {
        log.info("configuring gateway routes")
        configureGatewayRoutes()
    }

    log.info("configuring status page")
    install(StatusPageModule())

    val runners = environment.config.propertyOrNull("runners")?.getAs<RunnerConfig>() ?: RunnerConfig()
    // The ecom queue runner's JobRunner provider is registered by the ecommerce
    // module, so drop it when ecommerce is disabled — otherwise provide<JobRunner>
    // below would fail on a missing named provider.
    val enabledRunners = runners.enabled.filterNot { !Features.ecommerce && it == JobQueueNames.ecomRunner }
    log.info("enabled runners: ${enabledRunners.joinToString()}")

    RegisterJobsConfiguration()

    /**
     * Sometimes Koog & IntelliJ debug break things, clearing disables the debugger
     */
//    System.clearProperty("koog.features")

    val hubspotJob = async {
        val svc = provide<ConfigurationService>()
        withConnectionManager {
            withRequestCache {
                val cfg = svc.getValueAs<HubSpotConfiguration>("hubspot", provide())
                if (cfg != null && cfg.token.isNotBlank()) {
                    provides(singleton = true) {
                        HubSpot(cfg.token, configurationService = provide(), json = provide())
                    }
                } else {
                    providerMissing<HubSpot>()
                }
            }
        }
    }

    val jobJob = async {
        provide<JobEnqueueEventForwarder>().start()

        enabledRunners.forEach {
            launch {
                provide<JobRunner>(it).run()
            }
        }

        if (provide<SchedulerConfiguration>().enabled) {
            launch {
                provide<SchedulerRunner>().start()
            }
        }

        if (Features.analyticsProcessor) {
            launch {
                val consumer = provide<NatsEventConsumer>()
                while (true) {
                    try {
                        log.info("starting analytics event consumer")
                        consumer.run()
                    } catch (e: Exception) {
                        log.error("analytics event consumer failed, restarting in 5s", e)
                        delay(5_000)
                    }
                }
            }
        }

        PackageInstallations.install(this@module)
    }

    log.info("starting jobs")
    arrayOf(hubspotJob, jobJob).forEach {
        it.await()
    }
}
