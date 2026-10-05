package bosca.bible.components

import bosca.bible.Reference
import bosca.bible.style.Style
import bosca.bible.style.StyleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ComponentTest {

    @Test
    fun `IComponent sealed interface allows Break`() {
        val component: IComponent = Break()
        assertIs<Break>(component)
    }

    @Test
    fun `IComponent sealed interface allows Text`() {
        val component: IComponent = Text("hello", null)
        assertIs<Text>(component)
    }

    @Test
    fun `IComponent sealed interface allows VerseStart`() {
        val component: IComponent = VerseStart(Reference("GEN.1.1"))
        assertIs<VerseStart>(component)
    }

    @Test
    fun `IComponent sealed interface allows VerseEnd`() {
        val component: IComponent = VerseEnd()
        assertIs<VerseEnd>(component)
    }

    @Test
    fun `IComponent sealed interface allows ComponentContainer`() {
        val component: IComponent = ComponentContainer(ContainerType.DIV, emptyList(), null)
        assertIs<ComponentContainer>(component)
    }

    @Test
    fun `initializeStyles propagates to nested components in container`() {
        val style = Style(id = "test-style", color = "#FF0000")
        val registry = StyleRegistry()
        registry.register(listOf(style))

        val container = ComponentContainer(
            type = ContainerType.PARAGRAPH,
            components = listOf(
                Text("text", bosca.bible.style.StyleReference("test-style"))
            ),
            style = null
        )

        container.initializeStyles(registry)

        val textComponent = container.components[0] as Text
        assertEquals("#FF0000", textComponent.style?.color)
    }

    @Test
    fun `initializeStyles with null style does not throw`() {
        val registry = StyleRegistry()
        val br = Break()
        br.initializeStyles(registry)
        assertNull(br.style)
    }
}
