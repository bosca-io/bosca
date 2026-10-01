package bosca.recommendations.service

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [CohortCoEngagementConfiguration] — the tunable cohort co-engagement caps: their defaults, value
 * semantics, a JSON round-trip (how they persist in the strategy's `configuration`), and partial/empty-JSON
 * tolerance (an omitted field falls back to its default, so an older strategy's config still deserializes).
 */
class CohortCoEngagementConfigurationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(text: String) = json.decodeFromString(CohortCoEngagementConfiguration.serializer(), text)

    @Test
    fun `defaults are 200 200`() {
        val config = CohortCoEngagementConfiguration()
        assertEquals(200, config.perUserItemCap)
        assertEquals(200, config.perSourceCap)
    }

    @Test
    fun `value semantics - equality, copy, hashCode, toString`() {
        val a = CohortCoEngagementConfiguration(perUserItemCap = 150, perSourceCap = 300)
        assertEquals(a, a) // identity
        assertEquals(a, a.copy()) // structural equality of a distinct instance
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertNotEquals(a, a.copy(perUserItemCap = 10)) // differs in the first field
        assertNotEquals(a, a.copy(perSourceCap = 10)) // differs in the second field
        assertNotEquals<Any?>(a, "not a config") // different type
        assertTrue(a.toString().contains("150"))
    }

    @Test
    fun `round-trips through JSON`() {
        val config = CohortCoEngagementConfiguration(perUserItemCap = 150, perSourceCap = 300)
        val element = json.encodeToJsonElement(CohortCoEngagementConfiguration.serializer(), config)
        assertEquals(config, json.decodeFromJsonElement(CohortCoEngagementConfiguration.serializer(), element))
    }

    @Test
    fun `default-valued fields are omitted when encoding`() {
        // encodeDefaults=false (the default): a field equal to its default is not written, so a default
        // config serializes to an empty object and still round-trips back to the defaults.
        val element = json.encodeToString(CohortCoEngagementConfiguration.serializer(), CohortCoEngagementConfiguration())
        assertEquals("{}", element)
        assertEquals(CohortCoEngagementConfiguration(), decode(element))
        // A mixed config writes only the non-default field.
        assertEquals(
            """{"perUserItemCap":150}""",
            json.encodeToString(CohortCoEngagementConfiguration.serializer(), CohortCoEngagementConfiguration(perUserItemCap = 150)),
        )
    }

    @Test
    fun `an omitted field falls back to its default`() {
        // Each field independently defaults when absent, and an empty object yields all defaults.
        assertEquals(CohortCoEngagementConfiguration(75, 200), decode("""{"perUserItemCap": 75}"""))
        assertEquals(CohortCoEngagementConfiguration(200, 90), decode("""{"perSourceCap": 90}"""))
        assertEquals(CohortCoEngagementConfiguration(75, 90), decode("""{"perUserItemCap": 75, "perSourceCap": 90}"""))
        assertEquals(CohortCoEngagementConfiguration(200, 200), decode("{}"))
    }
}
