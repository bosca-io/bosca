@file:OptIn(ExperimentalUuidApi::class)

package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsScriptBinding
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AnalyticsScriptBindingTypeControllerTest {

    private val controller = AnalyticsScriptBindingTypeController()

    @Test
    fun `resolves every field straight from the model`() {
        val b = AnalyticsScriptBinding(
            id = Uuid.random(),
            scriptId = Uuid.random(),
            transform = false,
            enabled = false,
            ordinal = 7,
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
        )
        assertEquals(b.id, controller.id(b))
        assertEquals(b.scriptId, controller.scriptId(b))
        assertEquals(b.transform, controller.transform(b))
        assertEquals(b.enabled, controller.enabled(b))
        assertEquals(b.ordinal, controller.ordinal(b))
        assertEquals(b.created, controller.created(b))
        assertEquals(b.modified, controller.modified(b))
    }
}
