package bosca.bible.components

import bosca.bible.style.IStyle
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class BreakTest {

    @Test
    fun `default style is null`() {
        val br = Break()
        assertNull(br.style)
    }

    @Test
    fun `implements IComponent`() {
        val br = Break()
        assertIs<IComponent>(br)
    }

    @Test
    fun `custom style is preserved`() {
        val style = bosca.bible.style.Style(id = "break-style")
        val br = Break(style = style)
        assertIs<IStyle>(br.style)
        kotlin.test.assertEquals("break-style", br.style!!.id)
    }
}
