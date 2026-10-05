package bosca.ecommerce.graphql

import bosca.ecommerce.model.AccountAddress
import bosca.ecommerce.model.AddressType
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/** AccountAddress field wiring: every resolver returns the matching source field. */
@OptIn(ExperimentalUuidApi::class)
class AccountAddressControllerTest {

    private val controller = AccountAddressController()
    private val address = AccountAddress(
        id = UUID.random(),
        accountId = UUID.random(),
        type = AddressType.BILLING,
        preferred = true,
        address1 = "1 Main",
        address2 = "Apt 2",
        city = "Town",
        state = "ST",
        country = "US",
        zip = "00000",
        phone = "555-1234",
        note = "leave at door",
    )

    @Test
    fun `every field resolves from the source address`() {
        assertEquals(address.id, controller.id(address))
        assertEquals(AddressType.BILLING, controller.type(address))
        assertEquals(true, controller.preferred(address))
        assertEquals("1 Main", controller.address1(address))
        assertEquals("Apt 2", controller.address2(address))
        assertEquals("Town", controller.city(address))
        assertEquals("ST", controller.state(address))
        assertEquals("US", controller.country(address))
        assertEquals("00000", controller.zip(address))
        assertEquals("555-1234", controller.phone(address))
        assertEquals("leave at door", controller.note(address))
    }

    @Test
    fun `nullable fields resolve to null when unset`() {
        val minimal = address.copy(address2 = null, note = null)
        assertEquals(null, controller.address2(minimal))
        assertEquals(null, controller.note(minimal))
    }
}
