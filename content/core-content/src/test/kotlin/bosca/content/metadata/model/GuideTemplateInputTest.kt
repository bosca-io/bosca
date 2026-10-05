package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GuideTemplateInputTest {

    @Test
    fun `GuideTemplateInput stores all properties`() {
        val config = buildJsonObject { put("key", "value") }
        val defaultAttrs = buildJsonObject { put("attr", "val") }
        val templateId = Uuid.random()
        val moduleInput = GuideTemplateStepModuleInput(
            templateMetadataId = templateId,
            templateMetadataVersion = 1
        )
        val stepInput = GuideTemplateStepInput(
            modules = listOf(moduleInput),
            templateMetadataId = templateId,
            templateMetadataVersion = 2
        )

        val input = GuideTemplateInput(
            configuration = config,
            defaultAttributes = defaultAttrs,
            rrule = "FREQ=WEEKLY;COUNT=4",
            steps = listOf(stepInput),
            type = GuideType.LINEAR_PROGRESS
        )

        assertEquals(config, input.configuration)
        assertEquals(defaultAttrs, input.defaultAttributes)
        assertEquals("FREQ=WEEKLY;COUNT=4", input.rrule)
        assertEquals(1, input.steps.size)
        assertEquals(GuideType.LINEAR_PROGRESS, input.type)
    }

    @Test
    fun `GuideTemplateInput nullable fields can be null`() {
        val templateId = Uuid.random()
        val input = GuideTemplateInput(
            configuration = null,
            defaultAttributes = null,
            rrule = "FREQ=DAILY",
            steps = emptyList(),
            type = GuideType.CALENDAR
        )

        assertNull(input.configuration)
        assertNull(input.defaultAttributes)
    }

    @Test
    fun `GuideTemplateInput data class equality`() {
        val input1 = GuideTemplateInput(
            configuration = null,
            defaultAttributes = null,
            rrule = "FREQ=DAILY",
            steps = emptyList(),
            type = GuideType.LINEAR
        )
        val input2 = GuideTemplateInput(
            configuration = null,
            defaultAttributes = null,
            rrule = "FREQ=DAILY",
            steps = emptyList(),
            type = GuideType.LINEAR
        )
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }
}
