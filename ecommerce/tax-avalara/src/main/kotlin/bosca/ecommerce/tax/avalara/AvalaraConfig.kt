package bosca.ecommerce.tax.avalara

/**
 * Settings for the Avalara AvaTax integration, read from `ecom.tax.avalara.*` application config. The
 * [accountId] + [licenseKey] are the AvaTax credentials (Basic auth); [companyCode] selects the AvaTax
 * company profile; [origin] is the ship-from (nexus) address used as the transaction source. When any
 * credential is blank the calculator is treated as unconfigured and yields no tax (see
 * [AvalaraTaxCalculator]).
 */
data class AvalaraConfig(
    val accountId: String,
    val licenseKey: String,
    val companyCode: String,
    val origin: AvalaraAddress,
    val sandbox: Boolean = false,
) {
    /** True only when all three credentials are present — otherwise the calculator stays in no-tax mode. */
    val configured: Boolean
        get() = accountId.isNotBlank() && licenseKey.isNotBlank() && companyCode.isNotBlank()
}

/** A ship-from / ship-to postal address in the shape AvaTax's transaction API expects. */
data class AvalaraAddress(
    val line1: String,
    val city: String,
    val region: String,
    val postalCode: String,
    val country: String,
)
