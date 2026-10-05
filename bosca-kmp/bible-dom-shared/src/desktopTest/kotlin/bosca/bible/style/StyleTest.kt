package bosca.bible.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class StyleTest {

    @Test
    fun fieldPreservationWithAllFields() {
        val fontSize = Size(12f, SizeUnit.POINT)
        val margin = Margin(Size(5f, SizeUnit.POINT), null, null, null)
        val textIndent = Size(2f, SizeUnit.PERCENT)
        val style = Style(
            id = "heading1",
            fontFamily = "Arial",
            fontSize = fontSize,
            align = TextAlign.CENTER,
            fontWeight = FontWeight.BOLD,
            color = "#FF0000",
            margin = margin,
            whiteSpace = Whitespace.NOWRAP,
            verticalAlign = VerticalAlign.TEXT_TOP,
            textDecoration = TextDecoration.UNDERLINE,
            textIndent = textIndent
        )
        assertEquals("heading1", style.id)
        assertEquals("Arial", style.fontFamily)
        assertEquals(fontSize, style.fontSize)
        assertEquals(TextAlign.CENTER, style.align)
        assertEquals(FontWeight.BOLD, style.fontWeight)
        assertEquals("#FF0000", style.color)
        assertEquals(margin, style.margin)
        assertEquals(Whitespace.NOWRAP, style.whiteSpace)
        assertEquals(VerticalAlign.TEXT_TOP, style.verticalAlign)
        assertEquals(TextDecoration.UNDERLINE, style.textDecoration)
        assertEquals(textIndent, style.textIndent)
    }

    @Test
    fun defaultValuesAreNull() {
        val style = Style(id = "minimal")
        assertEquals("minimal", style.id)
        assertNull(style.fontFamily)
        assertNull(style.fontSize)
        assertNull(style.align)
        assertNull(style.fontWeight)
        assertNull(style.color)
        assertNull(style.margin)
        assertNull(style.whiteSpace)
        assertNull(style.verticalAlign)
        assertNull(style.textDecoration)
        assertNull(style.textIndent)
    }

    @Test
    fun equalityWhenFieldsMatch() {
        val a = Style(id = "s1", fontFamily = "Arial", fontWeight = FontWeight.BOLD)
        val b = Style(id = "s1", fontFamily = "Arial", fontWeight = FontWeight.BOLD)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun inequalityWhenIdDiffers() {
        val a = Style(id = "s1")
        val b = Style(id = "s2")
        assertNotEquals(a, b)
    }

    @Test
    fun inequalityWhenOptionalFieldDiffers() {
        val a = Style(id = "s1", color = "#000000")
        val b = Style(id = "s1", color = "#FFFFFF")
        assertNotEquals(a, b)
    }

    @Test
    fun toStringContainsId() {
        val style = Style(id = "test-style")
        val str = style.toString()
        assertEquals(true, str.contains("test-style"))
    }

    @Test
    fun toStringContainsAllFields() {
        val style = Style(
            id = "heading",
            fontFamily = "Times",
            fontSize = Size(14f, SizeUnit.POINT),
            align = TextAlign.LEFT,
            fontWeight = FontWeight.NORMAL,
            color = "#333",
            margin = null,
            whiteSpace = null,
            verticalAlign = null,
            textDecoration = null,
            textIndent = null
        )
        val str = style.toString()
        assertEquals(true, str.contains("heading"))
        assertEquals(true, str.contains("Times"))
        assertEquals(true, str.contains("#333"))
    }

    @Test
    fun initializeIsNoOp() {
        val registry = StyleRegistry()
        val style = Style(id = "s1", fontFamily = "Serif")
        style.initialize(registry)
        assertEquals("Serif", style.fontFamily)
    }

    @Test
    fun asSerializableReturnsJsonElement() {
        val style = Style(id = "s1", color = "#000")
        val json = style.asSerializable()
        assertEquals(true, json.toString().contains("s1"))
    }

    @Test
    fun copyCanOverrideId() {
        val original = Style(id = "old", fontFamily = "Arial")
        val copy = original.copy(id = "new")
        assertEquals("new", copy.id)
        assertEquals("Arial", copy.fontFamily)
    }
}
