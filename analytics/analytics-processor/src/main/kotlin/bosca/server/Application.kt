package bosca.server

import bosca.ai.configuration.AILlmExecutorModule
import bosca.analytics.configuration.JobQueueNames
import bosca.analytics.server.AnalyticsServerClientModule
import bosca.analytics.service.NatsEventConsumer
import bosca.di.AnalyticsProcessorProviderRegistrar
import bosca.di.AnalyticsProviderRegistrar
import bosca.di.AnalyticsAIProviderRegistrar
import bosca.di.ConfigurationProviderRegistrar
import bosca.di.CoreAnalyticsProviderRegistrar
import bosca.di.CoreConfigurationProviderRegistrar
import bosca.di.CorePipelinesProviderRegistrar
import bosca.di.CoreSchedulerProviderRegistrar
import bosca.di.CoreScriptingProviderRegistrar
import bosca.di.CoreSecurityProviderRegistrar
import bosca.di.CoreStorageProviderRegistrar
import bosca.di.PipelinesProviderRegistrar
import bosca.di.ProfileProviderRegistrar
import bosca.di.SchedulerProviderRegistrar
import bosca.di.ScriptingEngineProviderRegistrar
import bosca.di.ScriptingProviderRegistrar
import bosca.di.SecurityProviderRegistrar
import bosca.di.SlugProviderRegistrar
import bosca.di.StorageProviderRegistrar
import bosca.di.provide
import bosca.initialization.InitializeModule
import bosca.scheduler.listeners.JobEnqueueEventForwarder
import bosca.server.netty.NettyServerEngine
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.configuration.RegisterJobsConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    NettyServerEngine.start("application.yaml", args) { module() }
}

suspend fun BoscaApplication.module() {
    install(InitializeModule(
        providers = arrayOf(
            AnalyticsProcessorProviderRegistrar(),

            // Core providers
            CoreSecurityProviderRegistrar(),
            CoreAnalyticsProviderRegistrar(),
            CoreScriptingProviderRegistrar(),
            CorePipelinesProviderRegistrar(),
            CoreSchedulerProviderRegistrar(),
            CoreConfigurationProviderRegistrar(),
            CoreStorageProviderRegistrar(),

            // Providers
            AnalyticsProviderRegistrar(),
            AnalyticsAIProviderRegistrar(),
            SecurityProviderRegistrar(),
            ProfileProviderRegistrar(),
            SlugProviderRegistrar(),
            ScriptingProviderRegistrar(),
            ScriptingEngineProviderRegistrar(),
            PipelinesProviderRegistrar(),
            SchedulerProviderRegistrar(),
            ConfigurationProviderRegistrar(),
            StorageProviderRegistrar(),
        )
    ))

    // In-process error capture replacing the deleted SentryModule
    install(AnalyticsServerClientModule())

    log.info("configuring ai")
    install(AILlmExecutorModule())

    RegisterJobsConfiguration()

    launch {
        provide<JobEnqueueEventForwarder>().start()
    }

    launch {
        log.info("starting analytics job runner")
        provide<JobRunner>(JobQueueNames.analyticsRunner).run()
    }

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
