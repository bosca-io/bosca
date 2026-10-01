package bosca.content.metadata.model

import bosca.attributes.TemplateToolInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentTemplateContainerInputTest {

    @Test
    fun `DocumentTemplateContainerInput stores required fields`() {
        val input = DocumentTemplateContainerInput(
            id = "container-1",
            name = "Main",
            description = "Primary container"
        )
        assertEquals("container-1", input.id)
        assertEquals("Main", input.name)
        assertEquals("Primary container", input.description)
    }

    @Test
    fun `DocumentTemplateContainerInput optional fields default correctly`() {
        val input = DocumentTemplateContainerInput(
            id = "c", name = "n", description = "d"
        )
        assertNull(input.supplementaryKey)
        assertEquals(ContainerType.STANDARD, input.containerType)
        assertNull(input.tools)
        assertNull(input.renderers)
        assertNull(input.filters)
    }

    @Test
    fun `DocumentTemplateContainerInput stores all properties`() {
        val tool = TemplateToolInput(name = "tool-1")
        val renderer = ContainerRendererInput(name = "renderer-1")

        val input = DocumentTemplateContainerInput(
            id = "c-1",
            name = "Container",
            description = "desc",
            supplementaryKey = "sup-key",
            containerType = ContainerType.BIBLE,
            tools = listOf(tool),
            renderers = listOf(renderer),
            filters = listOf("filter1", "filter2")
        )

        assertEquals("c-1", input.id)
        assertEquals("Container", input.name)
        assertEquals("desc", input.description)
        assertEquals("sup-key", input.supplementaryKey)
        assertEquals(ContainerType.BIBLE, input.containerType)
        assertEquals(1, input.tools!!.size)
        assertEquals("tool-1", input.tools!![0].name)
        assertEquals(1, input.renderers!!.size)
        assertEquals("renderer-1", input.renderers!![0].name)
        assertEquals(listOf("filter1", "filter2"), input.filters)
    }

    @Test
    fun `DocumentTemplateContainerInput containerType can be null`() {
        val input = DocumentTemplateContainerInput(
            id = "c", name = "n", description = "d",
            containerType = null
        )
        assertNull(input.containerType)
    }
}
