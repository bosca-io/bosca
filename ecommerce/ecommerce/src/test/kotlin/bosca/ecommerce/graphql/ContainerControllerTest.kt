package bosca.ecommerce.graphql

import bosca.ecommerce.model.Container
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/** EcomContainer field wiring: every resolver returns the matching source field. */
@OptIn(ExperimentalUuidApi::class)
class ContainerControllerTest {

    private val controller = ContainerController()
    private val container = Container(
        id = UUID.random(), companyId = UUID.random(), name = "Small Box",
        width = 1.0, height = 2.0, length = 3.0, weight = 0.5,
        supportedWidth = 1.5, supportedHeight = 2.5, supportedLength = 3.5, supportedWeight = 10.0,
    )

    @Test
    fun `every field resolves from the source container`() {
        assertEquals(container.id, controller.id(container))
        assertEquals(container.companyId, controller.companyId(container))
        assertEquals("Small Box", controller.name(container))
        assertEquals(1.0, controller.width(container))
        assertEquals(2.0, controller.height(container))
        assertEquals(3.0, controller.length(container))
        assertEquals(0.5, controller.weight(container))
        assertEquals(1.5, controller.supportedWidth(container))
        assertEquals(2.5, controller.supportedHeight(container))
        assertEquals(3.5, controller.supportedLength(container))
        assertEquals(10.0, controller.supportedWeight(container))
        assertEquals(container.created, controller.created(container))
        assertEquals(container.modified, controller.modified(container))
    }
}
