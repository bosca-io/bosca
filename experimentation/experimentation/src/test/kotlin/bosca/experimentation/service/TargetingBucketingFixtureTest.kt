package bosca.experimentation.service

import bosca.experimentation.model.Rollout
import bosca.experimentation.model.VariationWeight
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-language parity test for the targeting-rule bucketing
 * algorithm. Loads `targeting_bucketing_fixture.json` from the test
 * resources and asserts that running
 * [ExperimentServiceImpl.bucketRollout] (and
 * [ExperimentServiceImpl.stableHash] for the rows that pin a hash)
 * against the fixture inputs produces the fixture's `expectedVariation`
 * for every row.
 *
 * The exact same fixture file is loaded by
 * `web/analytics/src/feature_flags_bucketing.spec.ts`, which runs the
 * TypeScript implementation of FNV-1a 64-bit + the same sorted-weight
 * bucket walk against the same inputs and asserts the same expected
 * variations. If either implementation drifts, its corresponding test
 * fails loudly — no silent disagreement between server-side and
 * (eventual) client-side bucketing.
 */
class TargetingBucketingFixtureTest {

    private val fixture: JsonObject by lazy {
        val stream = javaClass.classLoader.getResourceAsStream("targeting_bucketing_fixture.json")
            ?: error("targeting_bucketing_fixture.json not found on test classpath")
        val text = stream.bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `two-variation 50_50 fixture cases bucket as expected`() {
        val section = fixture["twoVariation_50_50"]!!.jsonObject
        val rollout = parseRollout(section["rolloutSorted"]!!.jsonArray.map { it.jsonObject })
        for (caseObj in section["cases"]!!.jsonArray.map { it.jsonObject }) {
            val flagKey = caseObj["flagKey"]!!.jsonPrimitive.content
            val salt = caseObj["salt"]!!.jsonPrimitive.content
            val ruleId = caseObj["ruleId"]!!.jsonPrimitive.content
            val identifier = caseObj["identifier"]!!.jsonPrimitive.content
            val expected = caseObj["expectedVariation"]!!.jsonPrimitive.content

            // Hash pinned where present so a future hash-algorithm change
            // is caught even if the bucket happens to land on the same
            // variation by coincidence.
            caseObj["expectedHash"]?.jsonPrimitive?.content?.toLong()?.let { expectedHash ->
                val actualHash = ExperimentServiceImpl.stableHash(
                    "$flagKey:$salt:$ruleId:$identifier"
                )
                assertEquals(
                    expectedHash, actualHash,
                    "stableHash drift for ($flagKey,$salt,$ruleId,$identifier) — " +
                        "did the FNV-1a constants or seed format change?"
                )
            }

            val actual = ExperimentServiceImpl.bucketRollout(flagKey, salt, ruleId, rollout, identifier)
            assertEquals(
                expected, actual,
                "bucketRollout($flagKey,$salt,$ruleId,$identifier) drifted from fixture"
            )
        }
    }

    @Test
    fun `three-variation 1_2_3 fixture cases bucket as expected`() {
        val section = fixture["threeVariation_1_2_3"]!!.jsonObject
        val rollout = parseRollout(section["rolloutSorted"]!!.jsonArray.map { it.jsonObject })
        for (caseObj in section["cases"]!!.jsonArray.map { it.jsonObject }) {
            val flagKey = caseObj["flagKey"]!!.jsonPrimitive.content
            val salt = caseObj["salt"]!!.jsonPrimitive.content
            val ruleId = caseObj["ruleId"]!!.jsonPrimitive.content
            val identifier = caseObj["identifier"]!!.jsonPrimitive.content
            val expected = caseObj["expectedVariation"]!!.jsonPrimitive.content
            val actual = ExperimentServiceImpl.bucketRollout(flagKey, salt, ruleId, rollout, identifier)
            assertEquals(
                expected, actual,
                "bucketRollout($flagKey,$salt,$ruleId,$identifier) drifted from fixture (3-way split)"
            )
        }
    }

    @Test
    fun `uniformity over 10000 ids matches the fixture distribution exactly`() {
        // With FNV-1a + a 50/50 split and the sequential id pattern
        // below, the actual distribution comes out exactly 5000/5000.
        // Pinning that exactly means any future change to the hash
        // function, the sort order, or the bucket-range walk fails
        // this test loudly instead of silently biasing assignments.
        val section = fixture["uniformity"]!!.jsonObject
        val rollout = parseRollout(section["rolloutSorted"]!!.jsonArray.map { it.jsonObject })
        val flagKey = section["flagKey"]!!.jsonPrimitive.content
        val salt = section["salt"]!!.jsonPrimitive.content
        val ruleId = section["ruleId"]!!.jsonPrimitive.content
        val n = section["n"]!!.jsonPrimitive.content.toInt()
        val expected = section["expectedDistribution"]!!.jsonObject
            .mapValues { it.value.jsonPrimitive.content.toInt() }

        val counts = mutableMapOf("control" to 0, "treatment" to 0)
        for (i in 0 until n) {
            val identifier = "user-${i.toString().padStart(5, '0')}"
            val v = ExperimentServiceImpl.bucketRollout(flagKey, salt, ruleId, rollout, identifier)
            counts[v] = (counts[v] ?: 0) + 1
        }
        assertEquals(expected, counts, "FNV-1a + sorted-weight bucketing distribution drifted")
    }

    @Test
    fun `bucketing is stable across repeated calls with the same inputs`() {
        // Determinism is the bedrock property of the whole system. If
        // this fails, every other test in this file becomes meaningless,
        // so it's worth pinning explicitly even though the fixture
        // expectations would also catch it.
        val rollout = Rollout(listOf(
            VariationWeight("a", 1),
            VariationWeight("b", 1),
            VariationWeight("c", 1),
        ))
        val first = ExperimentServiceImpl.bucketRollout("flag", "salt", "rule", rollout, "user-1")
        repeat(100) {
            val again = ExperimentServiceImpl.bucketRollout("flag", "salt", "rule", rollout, "user-1")
            assertEquals(first, again)
        }
    }

    @Test
    fun `bucketRollout never returns a variation outside the rollout`() {
        // Defense against off-by-one bugs in the cumulative-weight walk.
        val rollout = Rollout(listOf(
            VariationWeight("a", 7),
            VariationWeight("b", 13),
            VariationWeight("c", 23),
        ))
        val keys = setOf("a", "b", "c")
        for (i in 0 until 5000) {
            val v = ExperimentServiceImpl.bucketRollout("flag", "salt", "rule", rollout, "u-$i")
            assertTrue(v in keys, "bucketRollout returned out-of-set variation '$v'")
        }
    }

    private fun parseRollout(weights: List<JsonObject>): Rollout {
        return Rollout(weights.map {
            VariationWeight(
                variationKey = it["variationKey"]!!.jsonPrimitive.content,
                weight = it["weight"]!!.jsonPrimitive.content.toInt(),
            )
        })
    }
}
