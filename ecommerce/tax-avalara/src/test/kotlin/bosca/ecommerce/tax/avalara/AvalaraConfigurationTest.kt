package bosca.ecommerce.tax.avalara

import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Tax
import bosca.serialization.UUID
import bosca.server.config.ApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Verifies the DI provider reads `ecom.tax.avalara.*` and degrades to no-tax when unconfigured. */
@OptIn(ExperimentalUuidApi::class)
class AvalaraConfigurationTest {

    private fun appConfig(yaml: String) = ApplicationConfig.load(yaml.byteInputStream())

    private fun address() = CartAddress(
        cartId = UUID.random(), type = AddressType.SHIPPING, firstName = "A", lastName = "B",
        address1 = "1", city = "C", state = "CA", country = "US", zip = "94000", phone = "5",
    )

    @Test
    fun `builds an Avalara calculator from full config`() {
        val config = appConfig(
            """
            ecom:
              tax:
                avalara:
                  accountId: "111"
                  licenseKey: "key"
                  companyCode: "CO"
                  sandbox: true
                  origin:
                    line1: "1 A St"
                    city: "Reno"
                    region: "NV"
                    postalCode: "89501"
                    country: "US"
            """.trimIndent(),
        )
        val calculator = AvalaraConfiguration().avalaraTaxCalculator(config)
        assertTrue(calculator is AvalaraTaxCalculator)
    }

    @Test
    fun `degrades to a no-tax calculator when the config section is absent`() = runTest {
        val calculator = AvalaraConfiguration().avalaraTaxCalculator(appConfig("unrelated: true"))
        assertTrue(calculator is AvalaraTaxCalculator)
        // Unconfigured -> yields no tax even for a positive amount with a destination (no Avalara call made).
        assertEquals(Tax.ZERO, calculator.calculate(Money.of("100.00"), address()))
    }
}
