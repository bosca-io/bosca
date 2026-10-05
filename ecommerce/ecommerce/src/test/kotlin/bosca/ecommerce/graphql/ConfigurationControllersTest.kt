package bosca.ecommerce.graphql

import bosca.ecommerce.model.ClothingProductConfiguration
import bosca.ecommerce.model.EmptyAccountExtras
import bosca.ecommerce.model.EmptyCartItemConfiguration
import bosca.ecommerce.model.EmptyCatalogProductExtras
import bosca.ecommerce.model.EmptyCustomerExtras
import bosca.ecommerce.model.EmptyManufacturerExtras
import bosca.ecommerce.model.EmptyProviderConfiguration
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.ProviderConfigurationValue
import bosca.ecommerce.model.QuantityRequirements
import bosca.ecommerce.model.ShippingCartConfiguration
import bosca.ecommerce.model.ShippingRate
import bosca.ecommerce.model.SizeCartItemConfiguration
import bosca.ecommerce.model.StandardPlanConfiguration
import bosca.ecommerce.model.StandardProductConfiguration
import bosca.ecommerce.model.SubscriptionCartItemConfiguration
import bosca.ecommerce.model.SubscriptionProductConfiguration
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/**
 * Field wiring for the polymorphic configuration/extras union members: each exposes its `type`
 * discriminator (matching the `@SerialName`) plus any payload field.
 */
@OptIn(ExperimentalUuidApi::class)
class ConfigurationControllersTest {

    // --- ProductConfiguration ---

    @Test
    fun `standard product configuration exposes its discriminator`() {
        assertEquals("standard", StandardProductConfigurationController().type(StandardProductConfiguration))
    }

    @Test
    fun `clothing product configuration exposes its discriminator and sizes`() {
        val source = ClothingProductConfiguration(sizes = listOf("S", "M", "L"))
        val controller = ClothingProductConfigurationController()
        assertEquals("clothing", controller.type(source))
        assertEquals(listOf("S", "M", "L"), controller.sizes(source))
    }

    @Test
    fun `subscription product configuration exposes its discriminator and plan group id`() {
        val planGroupId = UUID.random()
        val source = SubscriptionProductConfiguration(planGroupId = planGroupId)
        val controller = SubscriptionProductConfigurationController()
        assertEquals("subscription", controller.type(source))
        assertEquals(planGroupId, controller.planGroupId(source))
    }

    // --- CartItemConfiguration ---

    @Test
    fun `empty cart item configuration exposes its discriminator`() {
        assertEquals("none", EmptyCartItemConfigurationController().type(EmptyCartItemConfiguration))
    }

    @Test
    fun `size cart item configuration exposes its discriminator and size`() {
        val source = SizeCartItemConfiguration(size = "M")
        val controller = SizeCartItemConfigurationController()
        assertEquals("size", controller.type(source))
        assertEquals("M", controller.size(source))
    }

    @Test
    fun `shipping cart configuration exposes its discriminator, selection, and options`() {
        val rate = ShippingRate(carrier = "USPS", serviceLevel = "Priority", token = "t", amount = Money.of("4.50"))
        val source = ShippingCartConfiguration(selected = rate, options = listOf(rate))
        val controller = ShippingCartConfigurationController()
        assertEquals("shipping", controller.type(source))
        assertEquals(rate, controller.selected(source))
        assertEquals(listOf(rate), controller.options(source))
    }

    @Test
    fun `subscription cart item configuration exposes its discriminator and plan id`() {
        val planId = UUID.random()
        val source = SubscriptionCartItemConfiguration(planId = planId)
        val controller = SubscriptionCartItemConfigurationController()
        assertEquals("subscription", controller.type(source))
        assertEquals(planId, controller.planId(source))
    }

    // --- CatalogProductExtras ---

    @Test
    fun `empty catalog product extras exposes its discriminator`() {
        assertEquals("none", EmptyCatalogProductExtrasController().type(EmptyCatalogProductExtras))
    }

    @Test
    fun `quantity requirements exposes its discriminator and bounds`() {
        val source = QuantityRequirements(min = 1, max = 10)
        val controller = QuantityRequirementsController()
        assertEquals("quantityRequirements", controller.type(source))
        assertEquals(1, controller.min(source))
        assertEquals(10, controller.max(source))
    }

    // --- ManufacturerExtras / AccountExtras / CustomerExtras ---

    @Test
    fun `empty manufacturer extras exposes its discriminator`() {
        assertEquals("none", EmptyManufacturerExtrasController().type(EmptyManufacturerExtras))
    }

    @Test
    fun `empty account extras exposes its discriminator`() {
        assertEquals("none", EmptyAccountExtrasController().type(EmptyAccountExtras))
    }

    @Test
    fun `empty customer extras exposes its discriminator`() {
        assertEquals("none", EmptyCustomerExtrasController().type(EmptyCustomerExtras))
    }

    // --- ProviderConfiguration ---

    @Test
    fun `empty provider configuration exposes its discriminator`() {
        assertEquals("none", EmptyProviderConfigurationController().type(EmptyProviderConfiguration))
    }

    @Test
    fun `key value provider configuration projects its map into typed entries`() {
        val source = KeyValueProviderConfiguration(values = mapOf("apiKey" to "secret"))
        val controller = KeyValueProviderConfigurationController()
        assertEquals("keyValue", controller.type(source))
        val values = controller.values(source)
        assertEquals(1, values.size)
        assertEquals("apiKey", values.first().key)
        assertEquals("secret", values.first().value)
    }

    @Test
    fun `provider configuration value exposes its key and value`() {
        val source = ProviderConfigurationValue(key = "endpoint", value = "https://example.test")
        val controller = ProviderConfigurationValueController()
        assertEquals("endpoint", controller.key(source))
        assertEquals("https://example.test", controller.value(source))
    }

    // --- PlanConfiguration ---

    @Test
    fun `standard plan configuration exposes its discriminator`() {
        assertEquals("standard", StandardPlanConfigurationController().type(StandardPlanConfiguration))
    }
}
