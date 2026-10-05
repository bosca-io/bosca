package bosca.slug.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the [String.slugify] extension function produces URL-safe slugs
 * by testing transliteration, diacritic removal, special character handling,
 * whitespace normalization, and edge cases such as blank or all-symbol input.
 */
class SlugifyTest {

    @Test
    fun `simple text is lowercased and spaces become hyphens`() {
        assertEquals("hello-world", "Hello World".slugify())
    }

    @Test
    fun `empty string returns n-a`() {
        assertEquals("n-a", "".slugify())
    }

    @Test
    fun `blank string returns n-a`() {
        assertEquals("n-a", "   ".slugify())
    }

    // --- Diacritics ---

    @Test
    fun `diacritics are stripped via Unicode normalization`() {
        assertEquals("cafe", "café".slugify())
    }

    @Test
    fun `diaeresis is stripped`() {
        assertEquals("naive", "naïve".slugify())
    }

    // --- Transliteration map ---

    @Test
    fun `German eszett is transliterated to ss`() {
        assertEquals("strasse", "straße".slugify())
    }

    @Test
    fun `Æ ligature is transliterated to ae`() {
        assertEquals("aether", "Æther".slugify())
    }

    @Test
    fun `OE ligature is transliterated to oe`() {
        assertEquals("oeuvre", "Œuvre".slugify())
    }

    @Test
    fun `Polish L with stroke is transliterated to l`() {
        assertEquals("lodz", "Łódź".slugify())
    }

    @Test
    fun `Icelandic thorn is transliterated to th`() {
        assertEquals("thor", "Þor".slugify())
    }

    @Test
    fun `Danish O with stroke is transliterated to o`() {
        assertEquals("o", "Ø".slugify())
    }

    @Test
    fun `Croatian D with stroke is transliterated to d`() {
        assertEquals("d", "Đ".slugify())
    }

    // --- Special characters ---

    @Test
    fun `ampersand is replaced with and`() {
        assertEquals("rock-and-roll", "rock & roll".slugify())
    }

    @Test
    fun `colon is replaced with hyphen`() {
        assertEquals("time-now", "time: now".slugify())
    }

    @Test
    fun `non-alphanumeric special characters are stripped`() {
        assertEquals("helloworld", "hello@world!".slugify())
    }

    // --- Whitespace and hyphen normalization ---

    @Test
    fun `multiple spaces are consolidated into a single hyphen`() {
        assertEquals("hello-world", "hello   world".slugify())
    }

    @Test
    fun `multiple consecutive hyphens are consolidated`() {
        assertEquals("hello-world", "hello---world".slugify())
    }

    @Test
    fun `leading and trailing hyphens are stripped`() {
        assertEquals("hello", "-hello-".slugify())
    }

    @Test
    fun `leading and trailing whitespace is trimmed before processing`() {
        assertEquals("hello", "  hello  ".slugify())
    }

    // --- Numbers ---

    @Test
    fun `numeric characters are preserved`() {
        assertEquals("test123", "test123".slugify())
    }

    // --- Mixed input ---

    @Test
    fun `mixed diacritics ampersand and colon produce correct slug`() {
        assertEquals("cafe-and-resume-a-story", "Café & Résumé: A Story".slugify())
    }

    // --- Edge: all characters stripped ---

    @Test
    fun `input consisting entirely of special characters returns n-a`() {
        assertEquals("n-a", "!@#\$%^".slugify())
    }
}
