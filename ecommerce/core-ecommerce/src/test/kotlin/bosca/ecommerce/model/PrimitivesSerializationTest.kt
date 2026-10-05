package bosca.ecommerce.model

import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * acceptance: round-trip serialization for Money, the sealed configuration/rule hierarchies
 * (including [SubscriptionProductConfiguration]), and the CartStatus flag set — using explicit
 * `.serializer()` (GraalVM native-safe), with the platform's contextual UUID serializer.
 */
@OptIn(ExperimentalUuidApi::class)
class PrimitivesSerializationTest {

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUID::class, UUIDSerializer()) }
    }

    private inline fun <reified T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, value: T): T {
        val encoded = json.encodeToString(serializer, value)
        return json.decodeFromString(serializer, encoded)
    }

    // --- Money ---

    @Test
    fun `money serializes as a scale-4 decimal string`() {
        assertEquals("\"19.9900\"", json.encodeToString(Money.serializer(), Money.of("19.99")))
        assertEquals(Money.of("19.99"), roundTrip(Money.serializer(), Money.of("19.99")))
    }

    @Test
    fun `money normalizes scale and arithmetic`() {
        assertEquals(Money.of("15.50"), Money.of("10.00") + Money.of("5.50"))
        assertEquals(Money.of("30.00"), Money.of("10.00") * 3)
        assertEquals(Money.of("2.50"), Money.of("10.00") / 4)
        assertEquals(Money.of("1.00"), Money.of("10.00").percentage(java.math.BigDecimal("10")))
        assertTrue(Money.ZERO.isZero)
        assertEquals(Money.of("5.00"), (-Money.of("5.00")).abs())
        assertEquals("0.0000", Money.ZERO.toString())
    }

    @Test
    fun `money uses HALF_EVEN rounding at scale 4`() {
        // drop a trailing 5 with an even preceding digit -> stays
        assertEquals("0.0000", Money.of("0.00005").toString())
        // drop a trailing 5 with an odd preceding digit -> rounds up
        assertEquals("0.0002", Money.of("0.00015").toString())
    }

    // --- Tax ---

    @Test
    fun `tax derives total and round-trips`() {
        val tax = Tax.of(country = Money.of("1.00"), state = Money.of("0.50"))
        assertEquals(Money.of("1.50"), tax.taxes)
        assertEquals(tax, roundTrip(Tax.serializer(), tax))
    }

    // --- Address ---

    @Test
    fun `address and address info round-trip`() {
        val info = AddressInfo(
            firstName = "Ada", lastName = "Lovelace",
            address1 = "1 Analytical Way", city = "London", state = "LDN", country = "GB", zip = "EC1",
            phone = "+44 20 0000 0000", email = "ada@example.com",
        )
        assertEquals(info, roundTrip(AddressInfo.serializer(), info))
        assertTrue(info.toAddress().valid)
        assertFalse(Address("", null, "", "", "", "").valid)
    }

    // --- CartStatus flags ---

    @Test
    fun `cart status flags round-trip through an int bitmask`() {
        val flags = setOf(CartStatusFlag.PAID, CartStatusFlag.PREPARING)
        val mask = CartStatus.of(*flags.toTypedArray())
        assertEquals(flags, mask.flags)
        assertTrue(mask.flags.contains(CartStatusFlag.PAID))
        assertFalse(mask.flags.contains(CartStatusFlag.REFUNDED))
        assertTrue((mask + CartStatusFlag.COMPLETE).has(CartStatusFlag.COMPLETE))
        assertFalse((mask - CartStatusFlag.PAID).has(CartStatusFlag.PAID))
    }

    // --- ProductConfiguration (incl. the subscription-binding variant) ---

    @Test
    fun `product configuration is polymorphic over the sealed hierarchy`() {
        val standard: ProductConfiguration = StandardProductConfiguration
        assertEquals(standard, roundTrip(ProductConfiguration.serializer(), standard))

        val clothing: ProductConfiguration = ClothingProductConfiguration(listOf("S", "M", "L"))
        assertEquals(clothing, roundTrip(ProductConfiguration.serializer(), clothing))

        val planGroupId = UUID.random()
        val sub: ProductConfiguration = SubscriptionProductConfiguration(planGroupId)
        val decoded = roundTrip(ProductConfiguration.serializer(), sub)
        assertEquals(sub, decoded)
        assertEquals(planGroupId, (decoded as SubscriptionProductConfiguration).planGroupId)
    }

    // --- Other sealed hierarchies ---

    @Test
    fun `catalog product extras round-trip`() {
        val extras: CatalogProductExtras = QuantityRequirements(min = 1, max = 10)
        assertEquals(extras, roundTrip(CatalogProductExtras.serializer(), extras))
        assertEquals(EmptyCatalogProductExtras, roundTrip(CatalogProductExtras.serializer(), EmptyCatalogProductExtras))
    }

    @Test
    fun `subscription extras carry a saved payment method`() {
        val extras: SubscriptionExtras = StandardSubscriptionExtras(SavedPaymentMethod("test", "tok_123"))
        val decoded = roundTrip(SubscriptionExtras.serializer(), extras)
        assertEquals(extras, decoded)
        assertEquals("tok_123", (decoded as StandardSubscriptionExtras).savedPaymentMethod?.token)
    }

    @Test
    fun `cart item configuration variants round-trip`() {
        val size: CartItemConfiguration = SizeCartItemConfiguration("M")
        assertEquals(size, roundTrip(CartItemConfiguration.serializer(), size))

        val shipping: CartItemConfiguration = ShippingCartConfiguration(
            selected = ShippingRate("USPS", "Priority", "1-3 days", "rate_1", Money.of("7.50")),
            options = listOf(ShippingRate("USPS", "Ground", null, "rate_2", Money.of("4.25"))),
        )
        assertEquals(shipping, roundTrip(CartItemConfiguration.serializer(), shipping))

        val planId = UUID.random()
        val plan: CartItemConfiguration = SubscriptionCartItemConfiguration(planId)
        assertEquals(plan, roundTrip(CartItemConfiguration.serializer(), plan))
    }

    @Test
    fun `manufacturer and plan configuration round-trip`() {
        assertEquals(EmptyManufacturerExtras, roundTrip(ManufacturerExtras.serializer(), EmptyManufacturerExtras))
        assertEquals(StandardPlanConfiguration, roundTrip(PlanConfiguration.serializer(), StandardPlanConfiguration))
    }

    @Test
    fun `account, cart, and customer extras round-trip`() {
        assertEquals(EmptyAccountExtras, roundTrip(AccountExtras.serializer(), EmptyAccountExtras))
        assertEquals(EmptyCartExtras, roundTrip(CartExtras.serializer(), EmptyCartExtras))
        assertEquals(EmptyCustomerExtras, roundTrip(CustomerExtras.serializer(), EmptyCustomerExtras))
    }

    @Test
    fun `provider configuration is polymorphic over empty and key-value variants`() {
        assertEquals(
            EmptyProviderConfiguration,
            roundTrip(ProviderConfiguration.serializer(), EmptyProviderConfiguration),
        )
        val kv: ProviderConfiguration = KeyValueProviderConfiguration(mapOf("apiKey" to "sk_test", "region" to "us"))
        val decoded = roundTrip(ProviderConfiguration.serializer(), kv)
        assertEquals(kv, decoded)
        assertEquals("sk_test", (decoded as KeyValueProviderConfiguration).values["apiKey"])
    }

    // --- Promotion rules ---

    @Test
    fun `promotion rules are polymorphic across cart and subscription rules`() {
        val cart: Rule = PercentOffCartRule(10.0)
        assertEquals(cart, roundTrip(Rule.serializer(), cart))

        val amountOff: CartRule = AmountOffCartRule(Money.of("5.00"))
        assertEquals(amountOff, roundTrip(CartRule.serializer(), amountOff))

        assertEquals(FreeShippingCartRule, roundTrip(Rule.serializer(), FreeShippingCartRule))

        val subRule: Rule = PercentOffSubscriptionRule(15.0)
        assertEquals(subRule, roundTrip(Rule.serializer(), subRule))

        val subAmount: SubscriptionRule = AmountOffSubscriptionRule(Money.of("2.00"))
        assertEquals(subAmount, roundTrip(SubscriptionRule.serializer(), subAmount))
    }
}
