package bosca.ecommerce.graphql

import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.CartAddress
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/** CartAddress field wiring: every resolver returns the matching source field. */
@OptIn(ExperimentalUuidApi::class)
class CartAddressControllerTest {

    private val controller = CartAddressController()
    private val source = CartAddress(
        id = UUID.random(),
        cartId = UUID.random(),
        type = AddressType.SHIPPING,
        firstName = "Ada",
        lastName = "Lovelace",
        address1 = "1 Analytical Way",
        address2 = "Suite 9",
        city = "London",
        state = "LDN",
        country = "GB",
        zip = "EC1",
        phone = "555-0100",
        email = "ada@example.com",
        note = "leave at door",
        validated = true,
    )

    @Test
    fun `every field resolves from the source address`() {
        assertEquals(source.id, controller.id(source))
        assertEquals(AddressType.SHIPPING, controller.type(source))
        assertEquals("Ada", controller.firstName(source))
        assertEquals("Lovelace", controller.lastName(source))
        assertEquals("1 Analytical Way", controller.address1(source))
        assertEquals("Suite 9", controller.address2(source))
        assertEquals("London", controller.city(source))
        assertEquals("LDN", controller.state(source))
        assertEquals("GB", controller.country(source))
        assertEquals("EC1", controller.zip(source))
        assertEquals("555-0100", controller.phone(source))
        assertEquals("ada@example.com", controller.email(source))
        assertEquals("leave at door", controller.note(source))
        assertEquals(true, controller.validated(source))
    }

    @Test
    fun `nullable fields are null when unset`() {
        val minimal = source.copy(address2 = null, email = null, note = null)
        assertEquals(null, controller.address2(minimal))
        assertEquals(null, controller.email(minimal))
        assertEquals(null, controller.note(minimal))
    }
}
