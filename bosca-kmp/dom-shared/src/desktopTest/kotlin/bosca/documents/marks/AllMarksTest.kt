package bosca.documents.marks

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllMarksTest {

    // ========================
    // Italic
    // ========================

    @Test
    fun `Italic creation with default null attributes`() {
        val italic = Italic()
        assertNull(italic.attributes)
    }

    @Test
    fun `Italic creation with explicit null attributes`() {
        val italic = Italic(attributes = null)
        assertNull(italic.attributes)
    }

    @Test
    fun `Italic implements Mark interface`() {
        val italic = Italic()
        assertTrue(italic is Mark)
    }

    // ========================
    // Underline
    // ========================

    @Test
    fun `Underline creation with default null attributes`() {
        val underline = Underline()
        assertNull(underline.attributes)
    }

    @Test
    fun `Underline creation with explicit null attributes`() {
        val underline = Underline(attributes = null)
        assertNull(underline.attributes)
    }

    @Test
    fun `Underline implements Mark interface`() {
        val underline = Underline()
        assertTrue(underline is Mark)
    }

    // ========================
    // Subscript
    // ========================

    @Test
    fun `Subscript creation with default null attributes`() {
        val subscript = Subscript()
        assertNull(subscript.attributes)
    }

    @Test
    fun `Subscript creation with explicit null attributes`() {
        val subscript = Subscript(attributes = null)
        assertNull(subscript.attributes)
    }

    @Test
    fun `Subscript implements Mark interface`() {
        val subscript = Subscript()
        assertTrue(subscript is Mark)
    }

    // ========================
    // Superscript
    // ========================

    @Test
    fun `Superscript creation with default null attributes`() {
        val superscript = Superscript()
        assertNull(superscript.attributes)
    }

    @Test
    fun `Superscript creation with explicit null attributes`() {
        val superscript = Superscript(attributes = null)
        assertNull(superscript.attributes)
    }

    @Test
    fun `Superscript implements Mark interface`() {
        val superscript = Superscript()
        assertTrue(superscript is Mark)
    }

    // ========================
    // Hidden
    // ========================

    @Test
    fun `Hidden creation with default null attributes`() {
        val hidden = Hidden()
        assertNull(hidden.attributes)
    }

    @Test
    fun `Hidden creation with explicit null attributes`() {
        val hidden = Hidden(attributes = null)
        assertNull(hidden.attributes)
    }

    @Test
    fun `Hidden implements Mark interface`() {
        val hidden = Hidden()
        assertTrue(hidden is Mark)
    }
}
