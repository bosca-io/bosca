package bosca.ecommerce.graphql

import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/** SubscriptionPlanGroup field wiring: every resolver returns the matching source field. */
@OptIn(ExperimentalUuidApi::class)
class SubscriptionPlanGroupControllerTest {

    private val controller = SubscriptionPlanGroupController()
    private val group = SubscriptionPlanGroup(
        id = UUID.random(), storeId = UUID.random(), key = "pro", name = "Pro",
        description = "Pro tier", paymentRetries = 5,
    )

    @Test
    fun `every field resolves from the source group`() {
        assertEquals(group.id, controller.id(group))
        assertEquals("pro", controller.key(group))
        assertEquals("Pro", controller.name(group))
        assertEquals("Pro tier", controller.description(group))
        assertEquals(5, controller.paymentRetries(group))
        assertEquals(group.created, controller.created(group))
        assertEquals(group.modified, controller.modified(group))
    }
}
