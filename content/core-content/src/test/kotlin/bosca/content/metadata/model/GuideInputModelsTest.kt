package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class GuideInputModelsTest {

    @Test
    fun `GuideInput stores all properties`() {
        val templateId = Uuid.random()
        val input = GuideInput(
            guideType = GuideType.CALENDAR,
            rrule = "FREQ=DAILY;COUNT=7",
            steps = emptyList(),
            templateMetadataId = templateId,
            templateMetadataVersion = 2
        )
        assertEquals(GuideType.CALENDAR, input.guideType)
        assertEquals("FREQ=DAILY;COUNT=7", input.rrule)
        assertEquals(0, input.steps.size)
        assertEquals(templateId, input.templateMetadataId)
        assertEquals(2, input.templateMetadataVersion)
    }

    @Test
    fun `GuideStepInput defaults`() {
        val input = GuideStepInput(modules = emptyList())
        assertNull(input.stepMetadataId)
        assertNull(input.stepMetadataVersion)
        assertNull(input.metadata)
        assertEquals(0, input.modules.size)
    }

    @Test
    fun `GuideStepModuleInput defaults`() {
        val input = GuideStepModuleInput()
        assertNull(input.metadata)
        assertNull(input.moduleMetadataId)
        assertNull(input.moduleMetadataVersion)
    }

    @Test
    fun `GuideStepModuleInput with values`() {
        val id = Uuid.random()
        val input = GuideStepModuleInput(
            moduleMetadataId = id,
            moduleMetadataVersion = 3
        )
        assertEquals(id, input.moduleMetadataId)
        assertEquals(3, input.moduleMetadataVersion)
    }
}
