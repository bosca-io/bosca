package bosca.bml.i18n

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * CLDR cardinal plural-category selection, **data-driven**: the rules are CLDR's
 * own published `plurals.json` (bundled as a resource, pinned CLDR version — the same source
 * ICU compiles its tables from), evaluated by a small interpreter of the UTS #35 rule syntax.
 * No hand-transcribed linguistics, no ICU4J (native-image constraint): every language CLDR
 * knows works, and adding a language to the platform needs no code change — the same
 * runtime-data philosophy as the locale policy itself.
 *
 * Counts are integers by design (`t(key, count)`), which collapses the rule operands: `n`/`i`
 * are the absolute count, the fraction operands (`v w f t`) and the compact exponent (`e`/`c`)
 * are zero. The client mirror is the browser's `Intl.PluralRules`; a shared ICU-generated
 * differential fixture pins this evaluator against real ICU across every bundled language.
 *
 * Unknown languages evaluate as CLDR root: OTHER for every count — the same answer
 * `Intl.PluralRules` gives, keeping server and client in agreement even off the map.
 */
object PluralRules {

    private val log = LoggerFactory.getLogger(PluralRules::class.java)

    /** The CLDR category [locale]'s language assigns to an integer [count]. */
    fun select(locale: Locale, count: Long): PluralCategory {
        val n = if (count == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(count)
        val languageRules = lookup(locale) ?: return PluralCategory.OTHER
        for ((category, condition) in languageRules) {
            if (condition.matches(n)) return category
        }
        return PluralCategory.OTHER
    }

    // Longest-prefix lookup, mirroring the data's own keys (`pt` and `pt-PT` both exist).
    private fun lookup(locale: Locale): List<Pair<PluralCategory, Condition>>? {
        var tag = locale.toLanguageTag().lowercase()
        while (true) {
            rulesByLanguage[tag]?.let { return it }
            val cut = tag.lastIndexOf('-')
            if (cut < 0) return null
            tag = tag.substring(0, cut)
        }
    }

    // ── CLDR data, parsed once ───────────────────────────────────────────────

    private const val RESOURCE = "/bosca/bml/i18n/plurals.json"

    private val rulesByLanguage: Map<String, List<Pair<PluralCategory, Condition>>> by lazy {
        try {
            val text = PluralRules::class.java.getResourceAsStream(RESOURCE)
                ?.use { it.readBytes().decodeToString() }
                ?: error("bundled CLDR resource $RESOURCE is missing")
            parseCldr(text)
        } catch (e: Exception) {
            // A missing/corrupt bundled resource is a build defect — the differential test
            // catches it long before production. Never fail a render over it: root behavior.
            log.error("bml i18n: failed to load CLDR plural rules ($RESOURCE); every language degrades to OTHER", e)
            emptyMap()
        }
    }

    private fun parseCldr(text: String): Map<String, List<Pair<PluralCategory, Condition>>> {
        val cardinal = Json.parseToJsonElement(text)
            .jsonObject.getValue("supplemental")
            .jsonObject.getValue("plurals-type-cardinal")
            .jsonObject
        val out = LinkedHashMap<String, List<Pair<PluralCategory, Condition>>>(cardinal.size * 2)
        for ((language, rules) in cardinal) {
            val byCategory = LinkedHashMap<PluralCategory, Condition>()
            for ((key, rule) in rules.jsonObject) {
                val category = key.substringAfterLast('-').uppercase()
                    .let { name -> PluralCategory.entries.firstOrNull { it.name == name } } ?: continue
                // Everything from '@' on is samples, not conditions.
                val condition = rule.jsonPrimitive.content.substringBefore('@').trim()
                byCategory[category] = try {
                    parseCondition(condition)
                } catch (e: Exception) {
                    log.warn("bml i18n: unparseable CLDR rule for {}/{} ('{}'): {}", language, category, condition, e.toString())
                    NEVER
                }
            }
            // Canonical evaluation order (zero..many, then other) regardless of file order.
            out[language.lowercase()] = PluralCategory.entries.mapNotNull { c -> byCategory[c]?.let { c to it } }
        }
        return out
    }

    // ── the UTS #35 condition interpreter (integer counts) ─────────────────

    /** `condition := and_condition ('or' and_condition)*` — empty matches always (`other`). */
    private class Condition(private val anyOf: List<List<Relation>>) {
        fun matches(n: Long): Boolean =
            anyOf.isEmpty() || anyOf.any { relations -> relations.all { it.matches(n) } }
    }

    /** `relation := operand ('%' value)? ('=' | '!=') range (',' range)*` */
    private class Relation(
        private val operand: Char,
        private val modulus: Long?,
        private val negated: Boolean,
        private val ranges: List<LongRange>,
    ) {
        fun matches(n: Long): Boolean {
            var value = when (operand) {
                'n', 'i' -> n
                'v', 'w', 'f', 't', 'e', 'c' -> 0L // integer counts: no fraction, no exponent
                else -> return false
            }
            modulus?.let { value %= it }
            val inRanges = ranges.any { value in it }
            return inRanges != negated
        }
    }

    private val NEVER = Condition(listOf(listOf(Relation('?', null, negated = false, ranges = emptyList()))))

    private fun parseCondition(text: String): Condition {
        if (text.isEmpty()) return Condition(emptyList())
        return Condition(
            text.split(" or ").map { andPart ->
                andPart.trim().split(" and ").map { parseRelation(it.trim()) }
            },
        )
    }

    private fun parseRelation(text: String): Relation {
        val match = RELATION.matchEntire(text)
            ?: throw IllegalArgumentException("not a plural relation: '$text'")
        val (operand, modulus, operator, rangeList) = match.destructured
        return Relation(
            operand = operand.single(),
            modulus = modulus.takeIf { it.isNotEmpty() }?.toLong(),
            negated = operator == "!=",
            ranges = rangeList.split(',').map { part ->
                val range = part.trim()
                val dots = range.indexOf("..")
                if (dots < 0) {
                    val value = range.toLong()
                    value..value
                } else {
                    range.substring(0, dots).toLong()..range.substring(dots + 2).toLong()
                }
            },
        )
    }

    private val RELATION = Regex("""([nivwftce])(?:\s*%\s*(\d+))?\s*(!?=)\s*([\d.]+(?:\s*,\s*[\d.]+)*)""")
}
