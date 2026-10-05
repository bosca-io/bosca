package bosca.ecommerce.graphql

import bosca.ecommerce.model.Audit
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.serialization.json.JsonPrimitive

/** EcomAudit field wiring: every resolver returns the matching source field (jsonb passes through). */
@OptIn(ExperimentalUuidApi::class)
class AuditControllerTest {

    private val controller = AuditController()
    private val before = JsonPrimitive("before")
    private val after = JsonPrimitive("after")
    private val details = JsonPrimitive("details")
    private val audit = Audit(
        id = UUID.random(), principalId = UUID.random(), profileId = UUID.random(), storeId = UUID.random(),
        entityType = "manufacturer", entityId = UUID.random(), action = "updated",
        before = before, after = after, details = details,
    )

    @Test
    fun `every field resolves from the source audit row`() {
        assertEquals(audit.id, controller.id(audit))
        assertEquals("manufacturer", controller.entityType(audit))
        assertEquals(audit.entityId, controller.entityId(audit))
        assertEquals("updated", controller.action(audit))
        assertEquals(audit.principalId, controller.principalId(audit))
        assertEquals(audit.profileId, controller.profileId(audit))
        assertEquals(audit.storeId, controller.storeId(audit))
        assertEquals(before, controller.before(audit))
        assertEquals(after, controller.after(audit))
        assertEquals(details, controller.details(audit))
        assertEquals(audit.created, controller.created(audit))
    }
}
