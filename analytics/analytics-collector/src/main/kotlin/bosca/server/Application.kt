package bosca.server

import bosca.analytics.server.AnalyticsServerClientModule
import bosca.di.AnalyticsCollectorProviderRegistrar
import bosca.di.AnalyticsProviderRegistrar
import bosca.di.CoreAnalyticsProviderRegistrar
import bosca.di.CoreSecurityProviderRegistrar
import bosca.di.CoreStorageProviderRegistrar
import bosca.di.ProfileProviderRegistrar
import bosca.di.SecurityProviderRegistrar
import bosca.di.SlugProviderRegistrar
import bosca.di.StorageProviderRegistrar
import bosca.initialization.InitializeModule
import bosca.installer.model.PackageInstallations
import bosca.routes.configureAnalyticsRoutes
import bosca.security.routes.SecurityRoutesModule
import bosca.server.netty.NettyServerEngine
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    NettyServerEngine.start("application.yaml", args) { module() }
}

suspend fun BoscaApplication.module() {
    install(InitializeModule(
        providers = arrayOf(
            AnalyticsCollectorProviderRegistrar(),
            AnalyticsProviderRegistrar(),
            CoreSecurityProviderRegistrar(),
            CoreAnalyticsProviderRegistrar(),
            CoreStorageProviderRegistrar(),
            SecurityProviderRegistrar(),
            ProfileProviderRegistrar(),
            SlugProviderRegistrar(),
            StorageProviderRegistrar(),
        )
    ))
    // In-process error capture pushing through the analytics pipeline.
    // Replaces the deleted SentryModule.
    install(AnalyticsServerClientModule())
    install(SecurityRoutesModule())
    configureAnalyticsRoutes()
    launch {
        PackageInstallations.install(this@module)
    }
}
