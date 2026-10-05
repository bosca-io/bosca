package bosca.content.transition.service

import bosca.content.transition.service.Transitioner.Companion.epochAttribute
import bosca.content.transition.service.Transitioner.Companion.getDelay
import bosca.content.transition.service.Transitioner.Companion.getEffectiveAdvertisedEpoch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Edge-case tests for the advertised/published transition helpers
 * in [Transitioner.Companion].
 *
 * These complement the broader [TransitionerTest], [TransitionerGetDelayTest],
 * and [TransitionerEpochAttributeTest] suites by focusing on boundary
 * conditions that were historically problematic in the race condition
 * where `setPendingState` was called with the original stateId before
 * `doTransition` had computed the effective target (which might redirect
 * to "advertised").
 */
class TransitionerAdvertisedPublishEdgeCaseTest {

    // ---------------------------------------------------------------
    // getEffectiveAdvertisedEpoch edge cases
    // ---------------------------------------------------------------

    @Test
    fun `advertised 1ms before published is effective`() {
        // Very close timestamps -- advertised should still be honoured
        val result = getEffectiveAdvertisedEpoch(999L, 1000L)
        assertEquals(999L, result)
    }

    @Test
    fun `advertised equals published exactly returns null`() {
        // Equal values mean advertised is NOT before published, so no redirect
        assertNull(getEffectiveAdvertisedEpoch(1000L, 1000L))
    }

    @Test
    fun `advertised 1ms after published returns null`() {
        assertNull(getEffectiveAdvertisedEpoch(1001L, 1000L))
    }

    @Test
    fun `advertised zero with published set returns null`() {
        // Zero is treated as "not set" (the guard is > 0)
        assertNull(getEffectiveAdvertisedEpoch(0L, 5000L))
    }

    @Test
    fun `advertised zero with published null returns null`() {
        assertNull(getEffectiveAdvertisedEpoch(0L, null))
    }

    @Test
    fun `advertised negative with published null returns null`() {
        assertNull(getEffectiveAdvertisedEpoch(-1L, null))
    }

    @Test
    fun `advertised negative with published set returns null`() {
        assertNull(getEffectiveAdvertisedEpoch(-100L, 5000L))
    }

    @Test
    fun `advertised 1 with published null is effective`() {
        // Smallest positive value should be valid
        assertEquals(1L, getEffectiveAdvertisedEpoch(1L, null))
    }

    @Test
    fun `advertised 1 with published 2 is effective`() {
        assertEquals(1L, getEffectiveAdvertisedEpoch(1L, 2L))
    }

    @Test
    fun `advertised Long MAX_VALUE with published null is effective`() {
        assertEquals(Long.MAX_VALUE, getEffectiveAdvertisedEpoch(Long.MAX_VALUE, null))
    }

    @Test
    fun `advertised Long MAX_VALUE minus 1 with published MAX_VALUE is effective`() {
        assertEquals(
            Long.MAX_VALUE - 1,
            getEffectiveAdvertisedEpoch(Long.MAX_VALUE - 1, Long.MAX_VALUE)
        )
    }

    @Test
    fun `advertised Long MAX_VALUE equals published MAX_VALUE returns null`() {
        assertNull(getEffectiveAdvertisedEpoch(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    // ---------------------------------------------------------------
    // epochAttribute edge cases
    // ---------------------------------------------------------------

    @Test
    fun `epochAttribute with empty string value returns null`() {
        val attributes = Json.parseToJsonElement("""{"published": ""}""")
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute with whitespace string value returns null`() {
        val attributes = Json.parseToJsonElement("""{"published": "  "}""")
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute with floating point number string returns null`() {
        // "1745000000000.5" is not a valid Long
        val attributes = Json.parseToJsonElement("""{"published": "1745000000000.5"}""")
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute with floating point JSON number truncates to long`() {
        // JSON numbers with decimal parts: JsonPrimitive.longOrNull returns null for non-integer values.
        // The string fallback tries content.toLongOrNull() which also fails for "1.5E12".
        val attributes = JsonObject(mapOf("published" to JsonPrimitive(1.5)))
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute with boolean value returns null`() {
        val attributes = Json.parseToJsonElement("""{"published": true}""")
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute with nested object value returns null`() {
        val attributes = Json.parseToJsonElement("""{"published": {"value": 123}}""")
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute with array value returns null`() {
        val attributes = JsonObject(mapOf("published" to JsonArray(listOf(JsonPrimitive(123)))))
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute with very large string number parses correctly`() {
        val largeEpoch = Long.MAX_VALUE.toString()
        val attributes = Json.parseToJsonElement("""{"published": "$largeEpoch"}""")
        assertEquals(Long.MAX_VALUE, attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute with negative string number parses correctly`() {
        val attributes = Json.parseToJsonElement("""{"published": "-1000"}""")
        assertEquals(-1000L, attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute with leading zeros in string parses correctly`() {
        val attributes = Json.parseToJsonElement("""{"published": "00100"}""")
        assertEquals(100L, attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute distinguishes advertised and published keys`() {
        val attributes = Json.parseToJsonElement(
            """{"advertised": 100, "published": 200}"""
        )
        assertEquals(100L, attributes.epochAttribute("advertised"))
        assertEquals(200L, attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute with only advertised key returns null for published`() {
        val attributes = Json.parseToJsonElement("""{"advertised": 100}""")
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute on null JsonElement returns null`() {
        val nullElement: kotlinx.serialization.json.JsonElement? = null
        assertNull(nullElement.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute on JsonPrimitive (not object) returns null`() {
        val primitive = JsonPrimitive(42)
        assertNull(primitive.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute on JsonArray returns null`() {
        val array = JsonArray(listOf(JsonPrimitive(42)))
        assertNull(array.epochAttribute("published"))
    }

    // ---------------------------------------------------------------
    // getDelay edge cases relevant to the advertised/published flow
    // ---------------------------------------------------------------

    @Test
    fun `getDelay with allowPast true returns value for epoch at zero`() {
        with(Transitioner.Companion) {
            // Epoch 0 is far in the past
            val result = 0L.getDelay(allowPast = true)
            assertNotNull(result)
        }
    }

    @Test
    fun `getDelay with allowPast false returns null for epoch at zero`() {
        with(Transitioner.Companion) {
            assertNull(0L.getDelay(allowPast = false))
        }
    }

    @Test
    fun `getDelay with allowPast true returns value for negative epoch`() {
        with(Transitioner.Companion) {
            val result = (-1000L).getDelay(allowPast = true)
            assertNotNull(result)
        }
    }

    @Test
    fun `getDelay default allowPast is false`() {
        with(Transitioner.Companion) {
            val pastTimestamp = System.currentTimeMillis() - 60_000L
            // Default (no argument) should be allowPast = false
            assertNull(pastTimestamp.getDelay())
        }
    }

    @Test
    fun `getDelay for timestamp just barely in the future returns non-null`() {
        with(Transitioner.Companion) {
            // 10 seconds in the future — should be valid
            val futureTimestamp = System.currentTimeMillis() + 10_000L
            assertNotNull(futureTimestamp.getDelay())
        }
    }

    // ---------------------------------------------------------------
    // Combined scenarios: epochAttribute + getEffectiveAdvertisedEpoch
    // ---------------------------------------------------------------

    @Test
    fun `combined scenario advertised string and published number both parse for effective check`() {
        val attributes = Json.parseToJsonElement(
            """{"advertised": "100", "published": 200}"""
        )
        val advertisedEpoch = attributes.epochAttribute("advertised")
        val publishedEpoch = attributes.epochAttribute("published")
        assertEquals(100L, advertisedEpoch)
        assertEquals(200L, publishedEpoch)
        assertEquals(100L, getEffectiveAdvertisedEpoch(advertisedEpoch, publishedEpoch))
    }

    @Test
    fun `combined scenario both string encoded both parse for effective check`() {
        val attributes = Json.parseToJsonElement(
            """{"advertised": "500", "published": "1000"}"""
        )
        val advertisedEpoch = attributes.epochAttribute("advertised")
        val publishedEpoch = attributes.epochAttribute("published")
        assertEquals(500L, advertisedEpoch)
        assertEquals(1000L, publishedEpoch)
        assertEquals(500L, getEffectiveAdvertisedEpoch(advertisedEpoch, publishedEpoch))
    }

    @Test
    fun `combined scenario advertised string equals published number returns null effective`() {
        val attributes = Json.parseToJsonElement(
            """{"advertised": "1000", "published": 1000}"""
        )
        val advertisedEpoch = attributes.epochAttribute("advertised")
        val publishedEpoch = attributes.epochAttribute("published")
        assertNull(getEffectiveAdvertisedEpoch(advertisedEpoch, publishedEpoch))
    }

    @Test
    fun `combined scenario advertised not-a-number returns null effective`() {
        val attributes = Json.parseToJsonElement(
            """{"advertised": "soon", "published": 1000}"""
        )
        val advertisedEpoch = attributes.epochAttribute("advertised")
        val publishedEpoch = attributes.epochAttribute("published")
        assertNull(advertisedEpoch)
        assertNull(getEffectiveAdvertisedEpoch(advertisedEpoch, publishedEpoch))
    }

    @Test
    fun `combined scenario advertised zero string returns null effective`() {
        val attributes = Json.parseToJsonElement(
            """{"advertised": "0", "published": 1000}"""
        )
        val advertisedEpoch = attributes.epochAttribute("advertised")
        val publishedEpoch = attributes.epochAttribute("published")
        assertEquals(0L, advertisedEpoch) // parser returns 0
        assertNull(getEffectiveAdvertisedEpoch(advertisedEpoch, publishedEpoch)) // but effective is null (0 <= 0)
    }
}
