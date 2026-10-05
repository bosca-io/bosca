package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class GuideTemplateStepContextTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    private fun createGuide() = Guide(
        metadataId = testId,
        version = 1,
        rrule = null,
        type = GuideType.LINEAR,
        templateMetadataId = null,
        templateMetadataVersion = null
    )

    private fun createTemplateStep() = GuideTemplateStep(
        metadataId = testId,
        version = 1,
        id = 10L,
        templateMetadataId = null,
        templateMetadataVersion = null,
        sort = 0
    )

    @Test
    fun `stores guide and step`() {
        val guide = createGuide()
        val step = createTemplateStep()
        val context = GuideTemplateStepContext(
            guide = guide,
            step = step,
            date = null
        )
        assertEquals(guide, context.guide)
        assertEquals(step, context.step)
    }

    @Test
    fun `date can be null`() {
        val guide = createGuide()
        val step = createTemplateStep()
        val context = GuideTemplateStepContext(guide = guide, step = step, date = null)
        assertNull(context.date)
    }
}
