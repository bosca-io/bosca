package bosca.bml.i18n

import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exhaustive proof: the CLDR-data-driven evaluator must assign the SAME
 * category real ICU does, for EVERY bundled language across every residue class the cardinal
 * integer rules can reference. The fixture is machine-generated from Node's Intl.PluralRules
 * (`bml-runtime/tools/generate-cldr-plurals.mjs`); no hand-written linguistic expectation
 * exists anywhere in this loop.
 */
class CldrPluralDifferentialTest {

    private val fixture = File("../bml-runtime/test/parity/cldr-plurals.json")

    private val categoryByLetter = mapOf(
        'z' to PluralCategory.ZERO,
        'o' to PluralCategory.ONE,
        't' to PluralCategory.TWO,
        'f' to PluralCategory.FEW,
        'm' to PluralCategory.MANY,
        'x' to PluralCategory.OTHER,
    )

    @Test
    fun `every language and count matches real ICU`() {
        assertTrue(fixture.isFile, "differential fixture missing at ${fixture.absolutePath} — run bml-runtime/tools/generate-cldr-plurals.mjs")
        val root = Json.parseToJsonElement(fixture.readText()).jsonObject
        val counts = root.getValue("counts").jsonArray.map { it.jsonPrimitive.long }
        val categories = root.getValue("categories").jsonObject

        var checked = 0
        val mismatches = mutableListOf<String>()
        for ((language, expectedLetters) in categories) {
            val expected = expectedLetters.jsonPrimitive.content
            val locale = Locale.forLanguageTag(language)
            counts.forEachIndexed { index, count ->
                val icu = categoryByLetter.getValue(expected[index])
                val ours = PluralRules.select(locale, count)
                checked++
                if (ours != icu) mismatches += "$language($count): ICU=$icu ours=$ours"
            }
        }
        assertTrue(checked > 50_000, "sweep unexpectedly small: $checked cells")
        assertEquals(
            emptyList(),
            mismatches.take(25),
            "CLDR evaluator disagrees with real ICU on ${mismatches.size} of $checked cells",
        )
    }
}
