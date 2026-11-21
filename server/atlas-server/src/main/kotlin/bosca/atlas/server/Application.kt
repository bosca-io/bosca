package bosca.atlas.server

import bosca.analytics.server.AnalyticsServerClientModule
import bosca.ai.configuration.AILlmExecutorModule
import bosca.artifacts.docker.routes.DockerRoutesModule
import bosca.artifacts.helm.routes.HelmRoutesModule
import bosca.artifacts.maven.routes.MavenRoutesModule
import bosca.artifacts.ml.routes.MlRoutesModule
import bosca.artifacts.npm.routes.NpmRoutesModule
import bosca.artifacts.raw.routes.RawRoutesModule
import bosca.atlas.server.graphql.AtlasGraphQLService
import bosca.configuration.GraphQLModule
import bosca.di.*
import bosca.git.dfs.DfsBlockCacheInitializer
import bosca.graphql.GraphQLRegistrar
import bosca.http.StatusPageModule
import bosca.initialization.InitializeModule
import bosca.installer.model.PackageInstallations
import bosca.routes.configureGitCiRoutes
import bosca.routes.configureGitRoutes
import bosca.routes.configureContentRoutes
import bosca.routes.configureScriptingRoutes
import bosca.routes.configureArtifactsRawRoutes
import bosca.routes.configureArtifactsMavenRoutes
import bosca.routes.configureArtifactsNpmRoutes
import bosca.routes.configureArtifactsHelmRoutes
import bosca.routes.configureArtifactsMlRoutes
import bosca.scheduler.listeners.JobEnqueueEventForwarder
import bosca.security.routes.SecurityRoutesModule
import bosca.security.routes.AuthenticationModule
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.configuration.RegisterJobsConfiguration
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.configuration.JobQueueNames
import bosca.pipelines.configuration.PipelinesJobQueueNames
import kotlinx.coroutines.launch

/** Starts Atlas's API, transports, optional BML listener, and selected background workers. */
fun main(args: Array<String>) {
    DfsBlockCacheInitializer.initialize()
    startAtlasServer(args)
}

/** Composes Git and registry services once, with HTTP routes and workers selected independently. */
suspend fun BoscaApplication.module(configure: BoscaApplication.() -> Unit = {}) {
    val configuration = AtlasConfiguration.from(environment.config)
    install(InitializeModule(providers = atlasProviderRegistrars()))
    configureAtlasHubSpot()
    configure()
    install(AILlmExecutorModule())
    install(AnalyticsServerClientModule())
    RegisterJobsConfiguration()
    // Completion callbacks may target a parent on a queue this process does not consume.
    provide<JobQueue>("git")
    provide<JobQueue>(PipelinesJobQueueNames.jobQueue)
    provide<JobQueue>(JobQueueNames.commonJobQueue)
    provide<JobQueue>(bosca.backup.BackupJobQueueNames.backupJobQueue)
    provide<JobQueue>(bosca.profile.configuration.JobQueueNames.profileJobQueue)
    provide<JobQueue>("workops")
    provide<JobQueue>(bosca.content.configuration.JobQueueNames.contentJobQueue)
    provide<JobQueue>(bosca.communications.configuration.JobQueueNames.messagesJobQueue)
    val forwarder = provide<JobEnqueueEventForwarder>()
    onShutdown { forwarder.stop() }
    forwarder.start()

    if (configuration.apiEnabled) {
        install(SecurityRoutesModule())
    } else if (configuration.gitEnabled || configuration.artifactsEnabled) {
        install(AuthenticationModule())
    }
    val graphql = AtlasGraphQLService(environment.config.propertyOrNull("graphql.introspection.enabled")?.getString()?.toBoolean() ?: false)
    if (configuration.apiEnabled) {
        install(GraphQLModule(graphql))
        configureContentRoutes()
        configureScriptingRoutes()
    } else {
        GraphQLRegistrar.register(graphql)
        launch { graphql.warmup() }
    }
    if (configuration.artifactsEnabled) {
        // Register the private registry namespace before Git's parameterized file route.
        configureArtifactsRawRoutes("/_artifacts")
        configureArtifactsMavenRoutes("/_artifacts")
        configureArtifactsNpmRoutes("/_artifacts")
        configureArtifactsHelmRoutes("/_artifacts")
        configureArtifactsMlRoutes("/_artifacts")
    }
    if (configuration.gitEnabled) {
        configureGitRoutes()
        configureGitCiRoutes()
    }
    if (configuration.artifactsEnabled) {
        install(DockerRoutesModule())
        install(HelmRoutesModule())
        install(MavenRoutesModule())
        install(NpmRoutesModule())
        install(RawRoutesModule())
        install(MlRoutesModule())
    }
    install(StatusPageModule())
    PackageInstallations.install(this)
    startAtlasWorkers(configuration)
}

internal fun atlasProviderRegistrars() = arrayOf(
    AtlasProviderRegistrar(),
    CoreAIProviderRegistrar(), AIProviderRegistrar(), KitProviderRegistrar(),
    CoreChatProviderRegistrar(), ChatProviderRegistrar(),
    CoreCollaborationProviderRegistrar(), CollaborationProviderRegistrar(),
    PostgresAdminProviderRegistrar(), NatsAdminProviderRegistrar(), MeilisearchAdminProviderRegistrar(),
    CoreProfileProviderRegistrar(), ProfileProviderRegistrar(),
    CoreFormsProviderRegistrar(), FormsProviderRegistrar(),
    CoreConfigurationProviderRegistrar(), ConfigurationProviderRegistrar(),
    bosca.di.BackupProviderRegistrar(),
    CoreSecurityProviderRegistrar(), SecurityProviderRegistrar(),
    CoreDevicesProviderRegistrar(), DevicesProviderRegistrar(),
    CoreStorageProviderRegistrar(), StorageProviderRegistrar(),
    CoreContentProviderRegistrar(), ContentProviderRegistrar(), SlugProviderRegistrar(),
    CoreCommentsProviderRegistrar(), CommentsProviderRegistrar(),
    CoreCalendarProviderRegistrar(), CalendarProviderRegistrar(),
    CoreLanguagesProviderRegistrar(), LanguagesProviderRegistrar(),
    CoreLocalizationProviderRegistrar(), LocalizationProviderRegistrar(),
    CoreWorkOpsProviderRegistrar(), WorkOpsProviderRegistrar(), StorePipelinesProviderRegistrar(),
    CoreKubernetesProviderRegistrar(), KubernetesProviderRegistrar(), KubernetesPipelinesProviderRegistrar(),
    CoreScriptingProviderRegistrar(), ScriptingProviderRegistrar(),
    CoreCommunicationsProviderRegistrar(), CommunicationsProviderRegistrar(),
    CoreAnalyticsProviderRegistrar(), AnalyticsProviderRegistrar(),
    CoreBibleProvidersProviderRegistrar(), BibleProvidersProviderRegistrar(), MuxProviderRegistrar(), HubSpotProviderRegistrar(),
    CoreSearchProviderRegistrar(), SearchProviderRegistrar(),
    CoreSchedulerProviderRegistrar(), SchedulerProviderRegistrar(),
    CorePipelinesProviderRegistrar(), PipelinesProviderRegistrar(),
    CoreGitProviderRegistrar(), GitProviderRegistrar(),
    CoreGitCiProviderRegistrar(), GitCiProviderRegistrar(), GitJobsProviderRegistrar(),
    CoreArtifactsProviderRegistrar(), ArtifactsProviderRegistrar(), ArtifactsGitProviderRegistrar(), ArtifactsAdminProviderRegistrar(),
    ArtifactsDockerProviderRegistrar(), ArtifactsHelmProviderRegistrar(), ArtifactsMavenProviderRegistrar(),
    ArtifactsNpmProviderRegistrar(), ArtifactsRawProviderRegistrar(), ArtifactsMlProviderRegistrar(),
)
