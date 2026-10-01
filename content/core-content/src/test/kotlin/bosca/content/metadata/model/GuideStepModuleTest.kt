package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class GuideStepModuleTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val moduleId = Uuid.parse("550e8400-e29b-41d4-a716-446655440001")

    @Test
    fun `GuideStepModule stores all properties`() {
        val module = GuideStepModule(
            id = 10,
            metadataId = testId,
            version = 2,
            step = 3L,
            moduleMetadataId = moduleId,
            moduleMetadataVersion = 4,
            sort = 1
        )
        assertEquals(10L, module.id)
        assertEquals(testId, module.metadataId)
        assertEquals(2, module.version)
        assertEquals(3L, module.step)
        assertEquals(moduleId, module.moduleMetadataId)
        assertEquals(4, module.moduleMetadataVersion)
        assertEquals(1, module.sort)
    }

    @Test
    fun `GuideStepModule id defaults to zero`() {
        val module = GuideStepModule(
            metadataId = testId,
            version = 1,
            step = 0L,
            moduleMetadataId = null,
            moduleMetadataVersion = null,
            sort = 0
        )
        assertEquals(0L, module.id)
    }

    @Test
    fun `GuideStepModule with null module metadata`() {
        val module = GuideStepModule(
            metadataId = testId,
            version = 1,
            step = 1L,
            moduleMetadataId = null,
            moduleMetadataVersion = null,
            sort = 0
        )
        assertNull(module.moduleMetadataId)
        assertNull(module.moduleMetadataVersion)
    }

    @Test
    fun `GuideStepModule key is null`() {
        val module = GuideStepModule(
            metadataId = testId,
            version = 1,
            step = 0L,
            moduleMetadataId = null,
            moduleMetadataVersion = null,
            sort = 0
        )
        assertNull(module.key)
    }

    @Test
    fun `GuideStepModule implements MetadataCacheKeyable`() {
        val module = GuideStepModule(
            metadataId = testId,
            version = 2,
            step = 5L,
            moduleMetadataId = null,
            moduleMetadataVersion = null,
            sort = 0
        )
        assertEquals(testId, module.metadataId)
        assertEquals(2, module.version)
        assertEquals(5L, module.step)
        assertNull(module.key)
    }

    @Test
    fun `GuideStepModule with different sort values`() {
        val module1 = GuideStepModule(
            metadataId = testId,
            version = 1,
            step = 0L,
            moduleMetadataId = null,
            moduleMetadataVersion = null,
            sort = 0
        )
        val module2 = GuideStepModule(
            metadataId = testId,
            version = 1,
            step = 0L,
            moduleMetadataId = null,
            moduleMetadataVersion = null,
            sort = 5
        )
        assertEquals(0, module1.sort)
        assertEquals(5, module2.sort)
    }
}
