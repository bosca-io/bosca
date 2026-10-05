package bosca.core.platform.providers

import bosca.core.platform.registerPlatformDependencies
import bosca.core.platform.currentAppVersion
import bosca.di.CoreProviderRegistrar
import bosca.di.ProviderRegistrar
import bosca.di.register
import bosca.di.provides

object Providers {

    /** Registers the app identity used by Bosca clients, then installs application and core providers. */
    fun initialize(appId: String, vararg providers: ProviderRegistrar) {
        initialize(BoscaClientConfig(appId, currentAppVersion()), *providers)
    }

    /** Registers explicit Bosca client identity, then installs application and core providers. */
    fun initialize(config: BoscaClientConfig, vararg providers: ProviderRegistrar) {
        provides<BoscaClientConfig>(singleton = true) { config }
        initialize(*providers)
    }

    fun initialize(vararg providers: ProviderRegistrar) {
        registerPlatformDependencies()
        val providers = providers.toMutableList()
        providers.add(CoreProviderRegistrar())
        register(*providers.toTypedArray())
    }
}
