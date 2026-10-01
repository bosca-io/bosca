package bosca.ecommerce.graphql

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/** The payments admin namespace exposes the id-scoped per-payment mutation accessor. */
@OptIn(ExperimentalUuidApi::class)
class PaymentsMutationControllerTest {

    private val controller = PaymentsMutationController()

    @Test
    fun `payment accessor returns the id-scoped mutation namespace`() {
        val id = UUID.random()
        assertEquals(id, controller.payment(id).id)
    }
}
