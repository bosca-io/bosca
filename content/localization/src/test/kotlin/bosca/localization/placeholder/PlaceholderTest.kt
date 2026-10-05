package bosca.localization.placeholder

import bosca.localization.model.LocalizationPlaceholder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceholderExtractorTest {

    @Test
    fun `extracts simple ICU placeholders with default string type`() {
        val placeholders = PlaceholderExtractor.extract("Hello {name}, welcome to {app}.")
        assertEquals(2, placeholders.size)
        assertEquals("name", placeholders[0].name)
        assertEquals("string", placeholders[0].type)
        assertEquals("app", placeholders[1].name)
    }

    @Test
    fun `extracts typed ICU placeholders`() {
        val placeholders = PlaceholderExtractor.extract("You have {count, number} messages from {when, date, short}.")
        assertEquals(2, placeholders.size)
        assertEquals("number", placeholders.first { it.name == "count" }.type)
        assertEquals("date", placeholders.first { it.name == "when" }.type)
    }

    @Test
    fun `deduplicates repeated placeholders preserving first-wins type`() {
        val placeholders = PlaceholderExtractor.extract("{x, number} and {x, date}")
        assertEquals(1, placeholders.size)
        assertEquals("number", placeholders[0].type, "first occurrence's type must win")
    }

    @Test
    fun `empty input produces empty list`() {
        assertTrue(PlaceholderExtractor.extract("").isEmpty())
    }

    @Test
    fun `text with no placeholders produces empty list`() {
        assertTrue(PlaceholderExtractor.extract("Plain text without braces").isEmpty())
    }

    @Test
    fun `names returns only placeholder names as a set`() {
        val names = PlaceholderExtractor.names("Hello {a}, {b}, {a}")
        assertEquals(setOf("a", "b"), names)
    }
}

class PlaceholderValidatorTest {

    @Test
    fun `translation missing a declared placeholder fails validation`() {
        val declared = listOf(LocalizationPlaceholder("name"), LocalizationPlaceholder("count", "number"))
        val result = PlaceholderValidator.validate(declared, "Hello {name}!")
        assertFalse(result.isValid)
        assertTrue("count" in result.missing)
    }

    @Test
    fun `translation with extra placeholder is not invalid but is flagged`() {
        val declared = listOf(LocalizationPlaceholder("name"))
        val result = PlaceholderValidator.validate(declared, "Hello {name}, {greeting}!")
        assertTrue(result.isValid)
        assertTrue("greeting" in result.extra)
    }

    @Test
    fun `full match is clean`() {
        val declared = listOf(LocalizationPlaceholder("a"), LocalizationPlaceholder("b"))
        val result = PlaceholderValidator.validate(declared, "{a} and {b}")
        assertTrue(result.isValid)
        assertTrue(result.missing.isEmpty())
        assertTrue(result.extra.isEmpty())
    }

    @Test
    fun `empty declared list against text with placeholders reports only extras`() {
        val result = PlaceholderValidator.validate(emptyList(), "{x}")
        assertTrue(result.isValid)
        assertTrue("x" in result.extra)
    }

    @Test
    fun `declared against empty text reports all as missing`() {
        val result = PlaceholderValidator.validate(listOf(LocalizationPlaceholder("a")), "")
        assertFalse(result.isValid)
        assertTrue("a" in result.missing)
    }
}

class PlaceholderConverterTest {

    @Test
    fun `ICU string placeholders convert to Android positional specifiers`() {
        val result = PlaceholderConverter.toAndroid("Hello {name}, you have {count, number} messages.")
        assertEquals("Hello %1\$s, you have %2\$d messages.", result)
    }

    @Test
    fun `repeated placeholder keeps the same positional index`() {
        val result = PlaceholderConverter.toAndroid("{name} logged in. Welcome {name}!")
        assertEquals("%1\$s logged in. Welcome %1\$s!", result)
    }

    @Test
    fun `Android conversion maps double and float types to f specifier`() {
        val result = PlaceholderConverter.toAndroid("Price: {price, double}")
        assertEquals("Price: %1\$f", result)
    }

    @Test
    fun `iOS conversion uses object specifier for strings and d for numbers`() {
        val result = PlaceholderConverter.toIos("Hello {name}, count={count, number}")
        assertEquals("Hello %@, count=%d", result)
    }

    @Test
    fun `iOS conversion uses f for float and double types`() {
        val result = PlaceholderConverter.toIos("Total: {total, float}")
        assertEquals("Total: %f", result)
    }

    @Test
    fun `Nuxt and ARB pass through ICU verbatim`() {
        val source = "Hello {name}, you have {count, number}."
        assertEquals(source, PlaceholderConverter.toNuxt(source))
        assertEquals(source, PlaceholderConverter.toArb(source))
    }

    @Test
    fun `empty string passes through all converters unchanged`() {
        assertEquals("", PlaceholderConverter.toAndroid(""))
        assertEquals("", PlaceholderConverter.toIos(""))
        assertEquals("", PlaceholderConverter.toNuxt(""))
        assertEquals("", PlaceholderConverter.toArb(""))
    }

    @Test
    fun `text with no placeholders passes through Android converter unchanged`() {
        val plain = "Just plain text."
        assertEquals(plain, PlaceholderConverter.toAndroid(plain))
    }
}
