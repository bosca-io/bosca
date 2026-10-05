package bosca.analytics.providers.instrumentation

import bosca.analytics.delivery.BoscaSinkConfig
import bosca.analytics.instrumentation.AutomaticInstrumentationOptions
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

/** Separates instrumentation configuration from collector delivery composition. */
@Providers
class AutomaticInstrumentationOptionsProvider {
    @Provider(singleton = true)
    fun options(config: BoscaSinkConfig): AutomaticInstrumentationOptions = config.automaticInstrumentation
}
