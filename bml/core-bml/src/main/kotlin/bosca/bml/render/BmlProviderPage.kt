package bosca.bml.render

import bosca.di.ProviderRegistrar

/** Optional library DI composition supplied by a compiled application page. */
interface BmlProviderPage {
    /** Direct producer registrars, in dependency order, for the application's used library graph. */
    val providerRegistrars: List<ProviderRegistrar>
}
