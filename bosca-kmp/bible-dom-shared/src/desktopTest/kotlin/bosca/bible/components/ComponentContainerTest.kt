package bosca.bible.components

import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComponentContainerTest {

    @Test
    fun `creation preserves type and components`() {
        val text = Text("Hello", null)
        val container = ComponentContainer(
            type = ContainerType.PARAGRAPH,
            components = listOf(text),
            style = null
        )
        assertEquals(ContainerType.PARAGRAPH, container.type)
        assertEquals(1, container.components.size)
        assertIs<Text>(container.components[0])
    }

    @Test
    fun `empty components list`() {
        val container = ComponentContainer(
            type = ContainerType.DIV,
            components = emptyList(),
            style = null
        )
        assertTrue(container.components.isEmpty())
    }

    @Test
    fun `style can be null`() {
        val container = ComponentContainer(
            type = ContainerType.SPAN,
            components = emptyList(),
            style = null
        )
        assertNull(container.style)
    }

    @Test
    fun `style can be set`() {
        val style = bosca.bible.style.Style(id = "container-style")
        val container = ComponentContainer(
            type = ContainerType.TABLE,
            components = emptyList(),
            style = style
        )
        assertEquals("container-style", container.style!!.id)
    }

    @Test
    fun `nested containers`() {
        val inner = ComponentContainer(
            type = ContainerType.SPAN,
            components = listOf(Text("nested", null)),
            style = null
        )
        val outer = ComponentContainer(
            type = ContainerType.DIV,
            components = listOf(inner),
            style = null
        )
        assertEquals(1, outer.components.size)
        assertIs<ComponentContainer>(outer.components[0])
        val innerFromOuter = outer.components[0] as ComponentContainer
        assertEquals(ContainerType.SPAN, innerFromOuter.type)
    }

    @Test
    fun `all ContainerType values are valid`() {
        val expected = setOf("DIV", "SPAN", "PARAGRAPH", "TABLE", "ROW", "COLUMN")
        val actual = ContainerType.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `toString contains type and components info`() {
        val container = ComponentContainer(
            type = ContainerType.PARAGRAPH,
            components = listOf(Text("test", null)),
            style = null
        )
        val str = container.toString()
        assertTrue(str.contains("PARAGRAPH"))
        assertTrue(str.contains("components"))
    }

    @Test
    fun `mixed component types in container`() {
        val components = listOf<IComponent>(
            VerseStart(Reference("GEN.1.1")),
            Text("In the beginning", null),
            Break(),
            VerseEnd()
        )
        val container = ComponentContainer(
            type = ContainerType.PARAGRAPH,
            components = components,
            style = null
        )
        assertEquals(4, container.components.size)
        assertIs<VerseStart>(container.components[0])
        assertIs<Text>(container.components[1])
        assertIs<Break>(container.components[2])
        assertIs<VerseEnd>(container.components[3])
    }

    @Test
    fun `implements IComponent`() {
        val container = ComponentContainer(
            type = ContainerType.DIV,
            components = emptyList(),
            style = null
        )
        assertIs<IComponent>(container)
    }
}
