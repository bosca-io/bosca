package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class GuideTemplateStepModuleTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val templateMetaId = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")

    @Test
    fun fieldsArePreserved() {
        val module = GuideTemplateStepModule(
            metadataId = testId,
            version = 2,
            step = 3L,
            id = 10,
            templateMetadataId = templateMetaId,
            templateMetadataVersion = 4,
            sort = 6
        )
        assertEquals(testId, module.metadataId)
        assertEquals(2, module.version)
        assertEquals(3L, module.step)
        assertEquals(10L, module.id)
        assertEquals(templateMetaId, module.templateMetadataId)
        assertEquals(4, module.templateMetadataVersion)
        assertEquals(6, module.sort)
    }

    @Test
    fun defaultIdIsNull() {
        val module = GuideTemplateStepModule(
            metadataId = testId,
            version = 1,
            step = 1L,
            templateMetadataId = null,
            templateMetadataVersion = null,
            sort = 0
        )
        assertNull(module.id)
    }

    @Test
    fun implementsMetadataCacheKeyable() {
        val module = GuideTemplateStepModule(
            metadataId = testId,
            version = 2,
            step = 5L,
            templateMetadataId = null,
            templateMetadataVersion = null,
            sort = 0
        )
        assertEquals(testId, module.metadataId)
        assertEquals(2, module.version)
        assertNull(module.key)
        assertEquals(5L, module.step)
    }
}
