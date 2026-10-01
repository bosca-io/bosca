package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class GuideStepTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val stepMetaId = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")

    @Test
    fun fieldsArePreserved() {
        val step = GuideStep(
            id = 42,
            metadataId = testId,
            version = 3,
            stepMetadataId = stepMetaId,
            stepMetadataVersion = 2,
            sort = 5
        )
        assertEquals(42L, step.id)
        assertEquals(testId, step.metadataId)
        assertEquals(3, step.version)
        assertEquals(stepMetaId, step.stepMetadataId)
        assertEquals(2, step.stepMetadataVersion)
        assertEquals(5, step.sort)
    }

    @Test
    fun defaultIdIsZero() {
        val step = GuideStep(
            metadataId = testId,
            version = 1,
            stepMetadataId = null,
            stepMetadataVersion = null,
            sort = 0
        )
        assertEquals(0L, step.id)
    }

    @Test
    fun implementsMetadataCacheKeyable() {
        val step = GuideStep(
            id = 10,
            metadataId = testId,
            version = 2,
            stepMetadataId = null,
            stepMetadataVersion = null,
            sort = 1
        )
        assertEquals(testId, step.metadataId)
        assertEquals(2, step.version)
        assertNull(step.key)
        assertNull(step.step)
    }

    @Test
    fun nullableFieldsCanBeNull() {
        val step = GuideStep(
            metadataId = testId,
            version = 1,
            stepMetadataId = null,
            stepMetadataVersion = null,
            sort = 0
        )
        assertNull(step.stepMetadataId)
        assertNull(step.stepMetadataVersion)
    }
}
