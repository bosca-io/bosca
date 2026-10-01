package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class GuideStepContextTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    private fun createGuide() = Guide(
        metadataId = testId,
        version = 1,
        rrule = null,
        type = GuideType.LINEAR,
        templateMetadataId = null,
        templateMetadataVersion = null
    )

    private fun createGuideStep(stepId: Long = 5L) = GuideStep(
        id = stepId,
        metadataId = testId,
        version = 1,
        stepMetadataId = null,
        stepMetadataVersion = null,
        sort = 0
    )

    @Test
    fun `stores guide and guideStep`() {
        val guide = createGuide()
        val step = createGuideStep()
        val context = GuideStepContext(
            guide = guide,
            guideStep = step,
            date = null
        )
        assertEquals(guide, context.guide)
        assertEquals(step, context.guideStep)
    }

    @Test
    fun `metadataId derived from guide`() {
        val guide = createGuide()
        val step = createGuideStep()
        val context = GuideStepContext(guide = guide, guideStep = step, date = null)
        assertEquals(testId, context.metadataId)
    }

    @Test
    fun `version derived from guide`() {
        val guide = createGuide()
        val step = createGuideStep()
        val context = GuideStepContext(guide = guide, guideStep = step, date = null)
        assertEquals(1, context.version)
    }

    @Test
    fun `step derived from guideStep id`() {
        val guide = createGuide()
        val step = createGuideStep(stepId = 42L)
        val context = GuideStepContext(guide = guide, guideStep = step, date = null)
        assertEquals(42L, context.step)
    }

    @Test
    fun `key is null`() {
        val guide = createGuide()
        val step = createGuideStep()
        val context = GuideStepContext(guide = guide, guideStep = step, date = null)
        assertNull(context.key)
    }

    @Test
    fun `date defaults to null when passed null`() {
        val guide = createGuide()
        val step = createGuideStep()
        val context = GuideStepContext(guide = guide, guideStep = step, date = null)
        assertNull(context.date)
    }
}
