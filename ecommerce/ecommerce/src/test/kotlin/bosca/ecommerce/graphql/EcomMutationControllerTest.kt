package bosca.ecommerce.graphql

import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Mutation namespace wiring: every field on `Mutation.ecom` hands back its singleton sub-mutation
 * marker object so per-area controllers can resolve their own fields.
 */
class EcomMutationControllerTest {

    private val controller = EcomMutationController()

    @Test
    fun `companies returns the CompaniesMutation marker`() {
        assertSame(CompaniesMutation, controller.companies())
    }

    @Test
    fun `customers returns the CustomersMutation marker`() {
        assertSame(CustomersMutation, controller.customers())
    }

    @Test
    fun `accounts returns the AccountsMutation marker`() {
        assertSame(AccountsMutation, controller.accounts())
    }

    @Test
    fun `catalogs returns the CatalogsMutation marker`() {
        assertSame(CatalogsMutation, controller.catalogs())
    }

    @Test
    fun `manufacturers returns the ManufacturersMutation marker`() {
        assertSame(ManufacturersMutation, controller.manufacturers())
    }

    @Test
    fun `products returns the ProductsMutation marker`() {
        assertSame(ProductsMutation, controller.products())
    }

    @Test
    fun `catalogProducts returns the CatalogProductsMutation marker`() {
        assertSame(CatalogProductsMutation, controller.catalogProducts())
    }

    @Test
    fun `stores returns the StoresMutation marker`() {
        assertSame(StoresMutation, controller.stores())
    }

    @Test
    fun `providers returns the ProvidersMutation marker`() {
        assertSame(ProvidersMutation, controller.providers())
    }

    @Test
    fun `fulfillment returns the FulfillmentMutation marker`() {
        assertSame(FulfillmentMutation, controller.fulfillment())
    }

    @Test
    fun `carts returns the CartsMutation marker`() {
        assertSame(CartsMutation, controller.carts())
    }

    @Test
    fun `promotions returns the PromotionsMutation marker`() {
        assertSame(PromotionsMutation, controller.promotions())
    }

    @Test
    fun `payments returns the PaymentsMutation marker`() {
        assertSame(PaymentsMutation, controller.payments())
    }

    @Test
    fun `plans returns the PlansMutation marker`() {
        assertSame(PlansMutation, controller.plans())
    }

    @Test
    fun `subscriptions returns the SubscriptionsMutation marker`() {
        assertSame(SubscriptionsMutation, controller.subscriptions())
    }

    @Test
    fun `containers returns the ContainersMutation marker`() {
        assertSame(ContainersMutation, controller.containers())
    }
}
