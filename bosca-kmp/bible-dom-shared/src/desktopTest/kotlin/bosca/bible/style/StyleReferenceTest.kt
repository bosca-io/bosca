package bosca.bible.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StyleReferenceTest {

    @Test
    fun idFieldPreservation() {
        val ref = StyleReference("heading")
        assertEquals("heading", ref.id)
    }

    @Test
    fun allPropertiesNullBeforeInitialize() {
        val ref = StyleReference("para")
        assertNull(ref.fontFamily)
        assertNull(ref.fontSize)
        assertNull(ref.align)
        assertNull(ref.fontWeight)
        assertNull(ref.color)
        assertNull(ref.margin)
        assertNull(ref.whiteSpace)
        assertNull(ref.verticalAlign)
        assertNull(ref.textDecoration)
        assertNull(ref.textIndent)
    }

    @Test
    fun initializeResolvesFromRegistry() {
        val registry = StyleRegistry()
        val style = Style(
            id = "bold-style",
            fontFamily = "Helvetica",
            fontSize = Size(16f, SizeUnit.POINT),
            align = TextAlign.CENTER,
            fontWeight = FontWeight.BOLD,
            color = "#FF0000",
            margin = Margin(Size(10f, SizeUnit.POINT), null, null, null),
            verticalAlign = VerticalAlign.TEXT_TOP,
            textDecoration = TextDecoration.UNDERLINE,
            textIndent = Size(2f, SizeUnit.PERCENT)
        )
        registry.register(listOf(style))

        val ref = StyleReference("bold-style")
        ref.initialize(registry)

        assertEquals("Helvetica", ref.fontFamily)
        assertEquals(Size(16f, SizeUnit.POINT), ref.fontSize)
        assertEquals(TextAlign.CENTER, ref.align)
        assertEquals(FontWeight.BOLD, ref.fontWeight)
        assertEquals("#FF0000", ref.color)
        assertEquals(Margin(Size(10f, SizeUnit.POINT), null, null, null), ref.margin)
        assertEquals(VerticalAlign.TEXT_TOP, ref.verticalAlign)
        assertEquals(TextDecoration.UNDERLINE, ref.textDecoration)
        assertEquals(Size(2f, SizeUnit.PERCENT), ref.textIndent)
    }

    @Test
    fun initializeWithMultipleSpaceSeparatedIds() {
        val registry = StyleRegistry()
        val style1 = Style(id = "base", fontFamily = "Arial", color = "#000")
        val style2 = Style(id = "override", fontFamily = "Helvetica", fontWeight = FontWeight.BOLD)
        registry.register(listOf(style1, style2))

        val ref = StyleReference("base override")
        ref.initialize(registry)

        assertEquals("Helvetica", ref.fontFamily)
        assertEquals("#000", ref.color)
        assertEquals(FontWeight.BOLD, ref.fontWeight)
    }

    @Test
    fun initializeSkipsUnknownIds() {
        val registry = StyleRegistry()
        val style = Style(id = "known", fontFamily = "Courier")
        registry.register(listOf(style))

        val ref = StyleReference("unknown known")
        ref.initialize(registry)

        assertEquals("Courier", ref.fontFamily)
    }

    @Test
    fun initializeWithNoMatchingStyleLeavesFieldsNull() {
        val registry = StyleRegistry()
        val ref = StyleReference("nonexistent")
        ref.initialize(registry)

        assertNull(ref.fontFamily)
        assertNull(ref.fontSize)
        assertNull(ref.fontWeight)
    }

    @Test
    fun laterStyleOverridesEarlierForSameProperty() {
        val registry = StyleRegistry()
        val style1 = Style(id = "first", fontSize = Size(10f, SizeUnit.POINT))
        val style2 = Style(id = "second", fontSize = Size(20f, SizeUnit.POINT))
        registry.register(listOf(style1, style2))

        val ref = StyleReference("first second")
        ref.initialize(registry)

        assertEquals(Size(20f, SizeUnit.POINT), ref.fontSize)
    }

    @Test
    fun asSerializableReturnsJsonElement() {
        val ref = StyleReference("test")
        val json = ref.asSerializable()
        assertEquals(true, json.toString().contains("test"))
    }

    @Test
    fun equalityById() {
        val a = StyleReference("x")
        val b = StyleReference("x")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
