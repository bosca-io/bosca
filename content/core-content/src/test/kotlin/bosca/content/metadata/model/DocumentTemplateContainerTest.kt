package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class DocumentTemplateContainerTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun fieldsArePreserved() {
        val container = DocumentTemplateContainer(
            metadataId = testId,
            version = 1,
            id = "container-1",
            name = "Main Container",
            description = "Primary content container",
            supplementaryKey = "supp",
            sort = 3,
            type = ContainerType.STANDARD
        )
        assertEquals(testId, container.metadataId)
        assertEquals(1, container.version)
        assertEquals("container-1", container.id)
        assertEquals("Main Container", container.name)
        assertEquals("Primary content container", container.description)
        assertEquals("supp", container.supplementaryKey)
        assertEquals(3, container.sort)
        assertEquals(ContainerType.STANDARD, container.type)
    }

    @Test
    fun defaultValues() {
        val container = DocumentTemplateContainer(
            metadataId = testId,
            version = 1,
            id = "c1",
            name = "n",
            description = "d",
            type = ContainerType.STANDARD
        )
        assertNull(container.supplementaryKey)
        assertEquals(0, container.sort)
        assertNull(container.tools)
        assertNull(container.renderers)
        assertNull(container.filters)
    }
}
