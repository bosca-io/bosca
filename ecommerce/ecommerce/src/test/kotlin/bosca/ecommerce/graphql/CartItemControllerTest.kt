package bosca.ecommerce.graphql

import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.CartStatus
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.SizeCartItemConfiguration
import bosca.ecommerce.model.Tax
import bosca.ecommerce.service.CatalogProductService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** CartItem field wiring: scalar fields read the source; catalogProduct resolves through the service. */
@OptIn(ExperimentalUuidApi::class)
class CartItemControllerTest {

    private val catalogProductService = mockk<CatalogProductService>()
    private val controller = CartItemController(catalogProductService)

    private val catalogProductId = UUID.random()
    private val source = CartItem(
        id = UUID.random(),
        catalogProductId = catalogProductId,
        type = ProductType.PHYSICAL,
        quantity = 3,
        status = CartStatus.of(CartStatusFlag.OPEN),
        baseRetailPrice = Money.of("12.00"),
        retailPrice = Money.of("11.00"),
        salesPrice = Money.of("10.00"),
        retailSubtotal = Money.of("33.00"),
        salesSubtotal = Money.of("30.00"),
        discounts = Money.of("3.00"),
        paid = Money.of("5.00"),
        taxes = Tax.of(state = Money.of("2.40")),
        expires = OffsetDateTime.now().plusSeconds(3600),
        parentId = UUID.random(),
        configuration = SizeCartItemConfiguration("L"),
    )

    @Test
    fun `scalar fields resolve from the source`() {
        assertEquals(source.id, controller.id(source))
        assertEquals(ProductType.PHYSICAL, controller.type(source))
        assertEquals(3, controller.quantity(source))
        assertEquals(listOf(CartStatusFlag.OPEN), controller.status(source))
        assertEquals(Money.of("12.00"), controller.baseRetailPrice(source))
        assertEquals(Money.of("11.00"), controller.retailPrice(source))
        assertEquals(Money.of("10.00"), controller.salesPrice(source))
        assertEquals(Money.of("33.00"), controller.retailSubtotal(source))
        assertEquals(Money.of("30.00"), controller.salesSubtotal(source))
        assertEquals(Money.of("3.00"), controller.discounts(source))
        assertEquals(Money.of("5.00"), controller.paid(source))
        assertEquals(source.taxes, controller.taxes(source))
        assertEquals(source.expires, controller.expires(source))
        assertEquals(source.parentId, controller.parentId(source))
        assertEquals(source.configuration, controller.configuration(source))
    }

    @Test
    fun `catalogProduct resolves through the service`() = runTest {
        val product = CatalogProduct(
            id = catalogProductId, catalogId = UUID.random(), productId = UUID.random(),
            type = ProductType.PHYSICAL, price = Money.of("10.00"),
        )
        coEvery { catalogProductService.get(catalogProductId) } returns product

        assertEquals(product, controller.catalogProduct(source))
    }

    @Test
    fun `catalogProduct errors when the product is missing`() = runTest {
        coEvery { catalogProductService.get(catalogProductId) } returns null

        assertFailsWith<IllegalStateException> { controller.catalogProduct(source) }
    }
}
