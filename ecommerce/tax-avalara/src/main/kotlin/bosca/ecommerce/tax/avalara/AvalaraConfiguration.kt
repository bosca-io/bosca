package bosca.ecommerce.tax.avalara

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.ecommerce.service.TaxCalculator
import bosca.server.config.ApplicationConfig
import org.slf4j.LoggerFactory

/**
 * DI registration for the Avalara tax calculator. Reads `ecom.tax.avalara.*` from application config
 * (env-substituted) and registers an [AvalaraTaxCalculator] as the module's [TaxCalculator]. Its own
 * `ProviderRegistrarPrefix` (EcommerceTaxAvalara → `EcommerceTaxAvalaraProviderRegistrar`) lets a
 * deployment include real tax only by depending on this module and registering that registrar; doing so
 * **replaces** the per-state `DefaultTaxCalculator` (both register the single `TaxCalculator` type, and
 * the later-loaded registrar wins). If the config section is absent or incomplete the calculator stays
 * in no-tax mode (logged once here) rather than failing startup.
 */
@Providers
class AvalaraConfiguration {

    @Provider(singleton = true)
    fun avalaraTaxCalculator(config: ApplicationConfig): TaxCalculator {
        val cfg = AvalaraConfig(
            accountId = config.value("$BASE.accountId"),
            licenseKey = config.value("$BASE.licenseKey"),
            companyCode = config.value("$BASE.companyCode"),
            sandbox = config.value("$BASE.sandbox").toBoolean(),
            origin = AvalaraAddress(
                line1 = config.value("$BASE.origin.line1"),
                city = config.value("$BASE.origin.city"),
                region = config.value("$BASE.origin.region"),
                postalCode = config.value("$BASE.origin.postalCode"),
                country = config.value("$BASE.origin.country"),
            ),
        )
        if (!cfg.configured) {
            log.warn("Avalara tax module is included but '{}' is not fully configured; tax will be zero until credentials are set", BASE)
        }
        return AvalaraTaxCalculator(cfg)
    }

    private fun ApplicationConfig.value(path: String): String = propertyOrNull(path)?.getString().orEmpty()

    private companion object {
        private const val BASE = "ecom.tax.avalara"
        private val log = LoggerFactory.getLogger(AvalaraConfiguration::class.java)
    }
}
