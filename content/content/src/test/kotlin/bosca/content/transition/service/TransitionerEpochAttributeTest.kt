package bosca.content.transition.service

import bosca.content.transition.service.Transitioner.Companion.epochAttribute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [Transitioner.Companion.epochAttribute].
 *
 * The helper exists because the JSON value for `published`/`advertised` epochs
 * can show up in two forms: as a JSON number (the canonical form written by the
 * editor's `applyAttributes`) or as a JSON string (which the editor's date input
 * can produce when invalid intermediate text is committed before a valid date is
 * typed). Both must parse to the same Long, otherwise the publish/advertise
 * scheduling diverges between [Transitioner] and the recursive call from
 * `MetadataTransitionExecutor`.
 */
class TransitionerEpochAttributeTest {

    @Test
    fun `returns null when attributes are null`() {
        assertNull(null.epochAttribute("published"))
    }

    @Test
    fun `returns null when key is missing`() {
        val attributes = Json.parseToJsonElement("""{"other": 1}""")
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `returns null when attribute value is JSON null`() {
        val attributes = JsonObject(mapOf("published" to JsonNull))
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `parses JSON number value`() {
        val attributes = Json.parseToJsonElement("""{"published": 1745000000000}""")
        assertEquals(1745000000000L, attributes.epochAttribute("published"))
    }

    @Test
    fun `parses JSON string value containing a number`() {
        val attributes = Json.parseToJsonElement("""{"published": "1745000000000"}""")
        assertEquals(1745000000000L, attributes.epochAttribute("published"))
    }

    @Test
    fun `returns null when string value is not a parseable long`() {
        val attributes = Json.parseToJsonElement("""{"published": "not-a-date"}""")
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `returns null when the attributes element is not a JSON object`() {
        val attributes = JsonPrimitive("scalar")
        assertNull(attributes.epochAttribute("published"))
    }

    @Test
    fun `parses zero as a valid epoch`() {
        // 0 is a valid Long; downstream code (getEffectiveAdvertisedEpoch /
        // the > 0 check in doTransition) is responsible for treating 0 as
        // "not set". The parser itself should not silently swallow it.
        val attributes = Json.parseToJsonElement("""{"published": 0}""")
        assertEquals(0L, attributes.epochAttribute("published"))
    }

    @Test
    fun `parses negative epoch value`() {
        val attributes = Json.parseToJsonElement("""{"published": -1}""")
        assertEquals(-1L, attributes.epochAttribute("published"))
    }

    @Test
    fun `reads requested key independently of other keys`() {
        val attributes = Json.parseToJsonElement(
            """{"published": 100, "advertised": 200, "other": "x"}"""
        )
        assertEquals(100L, attributes.epochAttribute("published"))
        assertEquals(200L, attributes.epochAttribute("advertised"))
        assertNull(attributes.epochAttribute("other"))
    }
}
