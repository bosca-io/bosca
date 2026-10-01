package bosca.bml.i18n

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CLDR cardinal selection for integer counts, per seeded language. Expected
 * values transcribed from the CLDR cardinal rules (integer branches).
 */
class PluralRulesTest {

    private fun select(tag: String, count: Long): PluralCategory =
        PluralRules.select(Locale.forLanguageTag(tag), count)

    private fun expect(tag: String, vararg cases: Pair<Long, PluralCategory>) {
        for ((count, category) in cases) {
            assertEquals(category, select(tag, count), "$tag($count)")
        }
    }

    @Test
    fun `english-like languages split one against other`() {
        for (tag in listOf("en", "nl", "sw", "te", "de")) {
            expect(
                tag,
                0L to PluralCategory.OTHER,
                1L to PluralCategory.ONE,
                2L to PluralCategory.OTHER,
                100L to PluralCategory.OTHER,
            )
        }
    }

    @Test
    fun `spanish adds the millions many, and es-419 shares es rules`() {
        for (tag in listOf("es", "es-419")) {
            expect(
                tag,
                0L to PluralCategory.OTHER,
                1L to PluralCategory.ONE,
                2L to PluralCategory.OTHER,
                1_000_000L to PluralCategory.MANY,
                2_000_000L to PluralCategory.MANY,
                1_000_001L to PluralCategory.OTHER,
            )
        }
    }

    @Test
    fun `french and portuguese treat 0 and 1 as one, with the millions many`() {
        for (tag in listOf("fr", "pt", "pt-BR")) {
            expect(
                tag,
                0L to PluralCategory.ONE,
                1L to PluralCategory.ONE,
                2L to PluralCategory.OTHER,
                1_000_000L to PluralCategory.MANY,
            )
        }
    }

    @Test
    fun `hindi treats 0 and 1 as one with no millions branch`() {
        expect(
            "hi",
            0L to PluralCategory.ONE,
            1L to PluralCategory.ONE,
            2L to PluralCategory.OTHER,
            1_000_000L to PluralCategory.OTHER,
        )
    }

    @Test
    fun `polish selects one, few, many by decade and teen rules`() {
        expect(
            "pl",
            1L to PluralCategory.ONE,
            2L to PluralCategory.FEW,
            4L to PluralCategory.FEW,
            5L to PluralCategory.MANY,
            12L to PluralCategory.MANY, // teens are many even though 12 % 10 = 2
            22L to PluralCategory.FEW,
            25L to PluralCategory.MANY,
            0L to PluralCategory.MANY,
            112L to PluralCategory.MANY,
        )
    }

    @Test
    fun `russian selects one for x1 outside the teens`() {
        expect(
            "ru",
            1L to PluralCategory.ONE,
            21L to PluralCategory.ONE,
            11L to PluralCategory.MANY, // 11 is a teen: many, not one
            2L to PluralCategory.FEW,
            24L to PluralCategory.FEW,
            14L to PluralCategory.MANY,
            0L to PluralCategory.MANY,
            5L to PluralCategory.MANY,
        )
    }

    @Test
    fun `filipino is one unless the count ends in 4, 6, or 9`() {
        for (tag in listOf("tl", "fil")) {
            expect(
                tag,
                0L to PluralCategory.ONE, // CLDR fil: 0 is "one"
                1L to PluralCategory.ONE,
                3L to PluralCategory.ONE,
                4L to PluralCategory.OTHER,
                6L to PluralCategory.OTHER,
                9L to PluralCategory.OTHER,
                10L to PluralCategory.ONE,
                14L to PluralCategory.OTHER,
                21L to PluralCategory.ONE,
            )
        }
    }

    @Test
    fun `korean never distinguishes plurals`() {
        expect(
            "ko",
            0L to PluralCategory.OTHER,
            1L to PluralCategory.OTHER,
            2L to PluralCategory.OTHER,
        )
    }

    @Test
    fun `unknown languages evaluate as CLDR root — OTHER for every count`() {
        // The same answer Intl.PluralRules gives for an unmapped language: server and client agree.
        expect(
            "xx",
            1L to PluralCategory.OTHER,
            0L to PluralCategory.OTHER,
            7L to PluralCategory.OTHER,
        )
    }

    @Test
    fun `arabic's six categories come straight from the CLDR data`() {
        expect(
            "ar",
            0L to PluralCategory.ZERO,
            1L to PluralCategory.ONE,
            2L to PluralCategory.TWO,
            3L to PluralCategory.FEW,
            11L to PluralCategory.MANY,
            100L to PluralCategory.OTHER,
        )
    }

    @Test
    fun `negative counts select by absolute value`() {
        assertEquals(PluralCategory.ONE, select("en", -1L))
        assertEquals(PluralCategory.FEW, select("pl", -3L))
    }
}
