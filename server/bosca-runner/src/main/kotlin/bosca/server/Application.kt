package bosca.server

import bosca.cache.withRequestCache
import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.db.withConnectionManager
import bosca.di.AIProviderRegistrar
import bosca.di.BackupProviderRegistrar
import bosca.di.AnalyticsAIProviderRegistrar
import bosca.di.AnalyticsProviderRegistrar
import bosca.di.ArtifactsProviderRegistrar
import bosca.di.BoscaRunnerProviderRegistrar
import bosca.di.CommentsProviderRegistrar
import bosca.di.CollaborationProviderRegistrar
import bosca.di.CommunityProviderRegistrar
import bosca.di.ConfigurationProviderRegistrar
import bosca.di.ContentProviderRegistrar
import bosca.di.DevicesProviderRegistrar
import bosca.di.MeilisearchAdminProviderRegistrar
import bosca.di.NatsAdminProviderRegistrar
import bosca.di.PostgresAdminProviderRegistrar
import bosca.di.CoreAnalyticsProviderRegistrar
import bosca.di.CoreArtifactsProviderRegistrar
import bosca.di.CoreCommunityProviderRegistrar
import bosca.di.CoreChatProviderRegistrar
import bosca.di.CoreCollaborationProviderRegistrar
import bosca.di.CoreConfigurationProviderRegistrar
import bosca.di.CoreGitCiProviderRegistrar
import bosca.di.CoreGitProviderRegistrar
import bosca.di.CoreWorkOpsProviderRegistrar
import bosca.di.CoreContentProviderRegistrar
import bosca.di.CoreDevicesProviderRegistrar
import bosca.di.CoreFormsProviderRegistrar
import bosca.di.CoreLanguagesProviderRegistrar
import bosca.di.CoreCommunicationsProviderRegistrar
import bosca.di.CoreProfileProviderRegistrar
import bosca.di.CoreSchedulerProviderRegistrar
import bosca.di.CorePipelinesProviderRegistrar
import bosca.di.CoreScriptingProviderRegistrar
import bosca.di.CoreEcommerceProviderRegistrar
import bosca.di.CoreKubernetesProviderRegistrar
import bosca.di.KubernetesProviderRegistrar
import bosca.di.KubernetesPipelinesProviderRegistrar
import bosca.di.CoreFeedsProviderRegistrar
import bosca.di.PipelinesProviderRegistrar
import bosca.di.EcommerceProviderRegistrar
import bosca.di.FeedsProviderRegistrar
import bosca.di.ScriptingEngineProviderRegistrar
import bosca.di.CoreSearchProviderRegistrar
import bosca.di.CoreSecurityProviderRegistrar
import bosca.di.CoreExperimentationProviderRegistrar
import bosca.di.CoreRecommendationsProviderRegistrar
import bosca.di.CoreSegmentationProviderRegistrar
import bosca.di.CoreStorageProviderRegistrar
import bosca.di.DiagnosticsProviderRegistrar
import bosca.di.GitCiProviderRegistrar
import bosca.di.GitJobsProviderRegistrar
import bosca.di.GitProviderRegistrar
import bosca.di.FormsProviderRegistrar
import bosca.di.HubSpotProviderRegistrar
import bosca.di.KitProviderRegistrar
import bosca.di.MuxProviderRegistrar
import bosca.di.LanguagesProviderRegistrar
import bosca.di.CommunicationsProviderRegistrar
import bosca.di.ChatProviderRegistrar
import bosca.di.ProfileProviderRegistrar
import bosca.di.SchedulerProviderRegistrar
import bosca.di.ScriptingProviderRegistrar
import bosca.di.SearchProviderRegistrar
import bosca.di.SecurityProviderRegistrar
import bosca.di.ExperimentationProviderRegistrar
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
import bosca.hubspot.client.HubSpot
import bosca.hubspot.configuration.HubSpotConfiguration
import bosca.initialization.InitializeModule
import bosca.installer.model.PackageInstallations
import bosca.scheduler.configuration.SchedulerConfiguration
import bosca.scheduler.listeners.JobEnqueueEventForwarder
import bosca.scheduler.runner.SchedulerRunner
import bosca.ai.configuration.AIModule
import bosca.analytics.server.AnalyticsServerClientModule
import bosca.analytics.service.NatsEventConsumer
import bosca.pipelines.configuration.PipelinesModule
import bosca.server.netty.NettyServerEngine
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.configuration.RegisterJobsConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
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
    val registrars = mutableListOf(
        BoscaRunnerProviderRegistrar(),

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
        CorePipelinesProviderRegistrar(),
        CoreFeedsProviderRegistrar(),
        CoreScriptingProviderRegistrar(),
        CoreFormsProviderRegistrar(),
        CoreSegmentationProviderRegistrar(),
        CoreRecommendationsProviderRegistrar(),
        CoreExperimentationProviderRegistrar(),
        CoreDevicesProviderRegistrar(),
        CoreGitProviderRegistrar(),
        CoreGitCiProviderRegistrar(),
        CoreWorkOpsProviderRegistrar(),
        CoreArtifactsProviderRegistrar(),

        // Providers
        ProfileProviderRegistrar(),
        SecurityProviderRegistrar(),
        SlugProviderRegistrar(),
        ContentProviderRegistrar(),
        CommunicationsProviderRegistrar(),
        SearchProviderRegistrar(),
        LanguagesProviderRegistrar(),
        AnalyticsProviderRegistrar(),
        ConfigurationProviderRegistrar(),
        SchedulerProviderRegistrar(),
        AIProviderRegistrar(),
        KitProviderRegistrar(),
        StorageProviderRegistrar(),
        GitProviderRegistrar(),
        GitCiProviderRegistrar(),
        GitJobsProviderRegistrar(),
        WorkOpsProviderRegistrar(),
        // Artifacts registry — required in the runner (registrar parity with bosca-server), not for
        // serving registry routes but so the git-ci requirement checker can construct the workops
        // RequiredArtifactVerifier, whose factory injects ArtifactRepositoryService. That checker
        // runs HERE via GitCiModule's bosca.artifacts.version.published listener and the
        // pipeline-requirement-check sweep; without ArtifactsProviderRegistrar its dependency is
        // unresolvable and gated jobs fail with "no artifact verifier is available in this deployment".
        ArtifactsProviderRegistrar(),
        StorePipelinesProviderRegistrar(),
        // Native release actions use the Kubernetes deploy adapters.
        // Only an actual Helm deploy needs the KubernetesControllerClient dependency.
        KubernetesPipelinesProviderRegistrar(),
        HubSpotProviderRegistrar(),
        MuxProviderRegistrar(),
        DiagnosticsProviderRegistrar(),
        PipelinesProviderRegistrar(),
        FeedsProviderRegistrar(),
        ScriptingProviderRegistrar(),
        ScriptingEngineProviderRegistrar(),
        BackupProviderRegistrar(),
        FormsProviderRegistrar(),
        SegmentationProviderRegistrar(),
        RecommendationsProviderRegistrar(),
        ExperimentationProviderRegistrar(),
        DevicesProviderRegistrar(),
        MeilisearchAdminProviderRegistrar(),
        NatsAdminProviderRegistrar(),
        PostgresAdminProviderRegistrar(),

        // AI
        AnalyticsAIProviderRegistrar()
    )

    // Comments live in the content domain (content/comments). Their moderation
    // job (comment-process) runs on the content queue, which this runner processes,
    // so the comments providers — including the job executor — must be registered
    // here too, matching the server (registrar parity).
    registrars.add(CommentsProviderRegistrar())

    if (Features.community) {
        registrars.add(CoreCommunityProviderRegistrar())
        registrars.add(CommunityProviderRegistrar())
    }

    if (Features.chat) {
        // Collaboration owns chat-mention notification nodes. Pipeline runs can execute on this
        // process, so keep these registrars in parity with bosca-server when chat is enabled.
        registrars.add(CoreChatProviderRegistrar())
        registrars.add(CoreCollaborationProviderRegistrar())
        registrars.add(ChatProviderRegistrar())
        registrars.add(CollaborationProviderRegistrar())
    }

    if (Features.ecommerce) {
        registrars.add(CoreEcommerceProviderRegistrar())
        registrars.add(EcommerceProviderRegistrar())
    }

    // Release deploys run wherever the pipeline-run queue is processed —
    // which can be this runner — so the kubernetes client providers must be registered here too
    // (registrar parity with bosca-server). The kubernetes-pipelines NODES are registered
    // unconditionally above; this gate only supplies the controller client a real deploy needs.
    if (Features.kubernetes) {
        registrars.add(CoreKubernetesProviderRegistrar())
        registrars.add(KubernetesProviderRegistrar())
    }

    log.info("enabled providers: ${registrars.joinToString { it.javaClass.simpleName }}")
    install(InitializeModule(providers = registrars.toTypedArray()))

    // In-process error capture replacing the deleted SentryModule
    install(AnalyticsServerClientModule())

    log.info("configuring ai")
    install(AIModule())

    log.info("configuring pipelines git sync")
    install(PipelinesModule())

    log.info("configuring workops listeners")
    install(bosca.workops.WorkOpsModule())

    log.info("configuring analytics listeners")
    install(bosca.analytics.AnalyticsModule())

    log.info("configuring communications listeners")
    install(bosca.communications.CommunicationsModule())

    log.info("configuring scripting listeners")
    install(bosca.scripting.ScriptingModule())

    log.info("configuring git ci requirement gate")
    install(bosca.git.ci.configuration.GitCiModule())

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
    System.clearProperty("koog.features")

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
                log.info("starting job runner: $it")
                provide<JobRunner>(it).run()
            }
        }

        if (provide<SchedulerConfiguration>().enabled) {
            launch {
                provide<SchedulerRunner>().start()
            }
        }

        PackageInstallations.install(this@module)
    }

    log.info("starting jobs")
    arrayOf(hubspotJob, jobJob).forEach {
        it.await()
    }

    // Run the analytics event consumer as a detached, supervised background task instead of
    // awaiting it. Its retry loop never returns, so keeping it inside the awaited `jobJob`
    // (the runner enables analyticsProcessor, unlike the server) blocked `module()` from ever
    // returning — which prevented NettyServerEngine.start() from binding the HTTP port, so the
    // runner served no health endpoints and the liveness probe could never pass. Launching it
    // on the application's SupervisorJob lets `module()` return so the HTTP server starts; the
    // consumer still runs for the process lifetime and is cancelled on shutdown.
    if (Features.analyticsProcessor) {
        launch {
            val consumer = provide<NatsEventConsumer>()
            while (true) {
                try {
                    log.info("starting analytics event consumer")
                    consumer.run()
                } catch (e: Exception) {
                    log.error("analytics event consumer failed, restarting in 5s", e)
                    delay(5_000.milliseconds)
                }
            }
        }
    }
}
