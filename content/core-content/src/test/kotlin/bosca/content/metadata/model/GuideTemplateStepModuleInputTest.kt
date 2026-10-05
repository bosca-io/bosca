package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class GuideTemplateStepModuleInputTest {

    @Test
    fun `GuideTemplateStepModuleInput stores all properties`() {
        val templateId = Uuid.random()
        val input = GuideTemplateStepModuleInput(
            templateMetadataId = templateId,
            templateMetadataVersion = 5
        )
        assertEquals(templateId, input.templateMetadataId)
        assertEquals(5, input.templateMetadataVersion)
    }

    @Test
    fun `GuideTemplateStepModuleInput data class equality`() {
        val id = Uuid.random()
        val input1 = GuideTemplateStepModuleInput(templateMetadataId = id, templateMetadataVersion = 1)
        val input2 = GuideTemplateStepModuleInput(templateMetadataId = id, templateMetadataVersion = 1)
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `GuideTemplateStepModuleInput inequality on different version`() {
        val id = Uuid.random()
        val input1 = GuideTemplateStepModuleInput(templateMetadataId = id, templateMetadataVersion = 1)
        val input2 = GuideTemplateStepModuleInput(templateMetadataId = id, templateMetadataVersion = 2)
        assertNotEquals(input1, input2)
    }

    @Test
    fun `GuideTemplateStepModuleInput copy preserves unchanged fields`() {
        val id = Uuid.random()
        val input = GuideTemplateStepModuleInput(templateMetadataId = id, templateMetadataVersion = 1)
        val copied = input.copy(templateMetadataVersion = 3)
        assertEquals(id, copied.templateMetadataId)
        assertEquals(3, copied.templateMetadataVersion)
    }
}
