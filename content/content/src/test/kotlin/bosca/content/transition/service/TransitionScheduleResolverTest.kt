package bosca.content.transition.service

import bosca.content.collection.model.ContentItem
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.service.TransitionScheduleResolver.epochAttribute
import bosca.content.transition.service.TransitionScheduleResolver.getEffectiveAdvertisedEpoch
import bosca.content.transition.service.TransitionScheduleResolver.toFutureDelay
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [TransitionScheduleResolver], which computes scheduling delays and
 * state redirects for workflow transitions based on content epoch attributes.
 */
class TransitionScheduleResolverTest {

    // ------------------------------------------------------------------
    // resolve(): basic cases
    // ------------------------------------------------------------------

    @Test
    fun `resolve with non-publish state returns same state and no delay`() {
        val request = beginTransition(stateId = "draft")
        val item = fakeContentItem(workflowStateId = "pending")

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("draft", schedule.effectiveStateId)
        assertNull(schedule.delay)
        assertEquals("draft", schedule.request.stateId)
    }

    @Test
    fun `resolve with explicit stateValid in future sets delay`() {
        val futureTime = OffsetDateTime.now().plusHours(1)
        val request = beginTransition(stateId = "draft", stateValid = futureTime)
        val item = fakeContentItem(workflowStateId = "pending")

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("draft", schedule.effectiveStateId)
        assertNotNull(schedule.delay)
    }

    @Test
    fun `resolve with explicit stateValid in past sets no delay`() {
        val pastTime = OffsetDateTime.now().minusHours(1)
        val request = beginTransition(stateId = "draft", stateValid = pastTime)
        val item = fakeContentItem(workflowStateId = "pending")

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertNull(schedule.delay)
    }

    // ------------------------------------------------------------------
    // resolve(): advertised redirect logic
    // ------------------------------------------------------------------

    @Test
    fun `resolve redirects published to advertised when advertised epoch precedes published`() {
        val futureAdvertised = System.currentTimeMillis() + 60_000
        val futurePublished = System.currentTimeMillis() + 120_000
        val attrs = JsonObject(mapOf(
            "advertised" to JsonPrimitive(futureAdvertised),
            "published" to JsonPrimitive(futurePublished),
        ))
        val request = beginTransition(stateId = "published")
        val item = fakeContentItem(workflowStateId = "draft", attributes = attrs)

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("advertised", schedule.effectiveStateId)
        assertEquals("advertised", schedule.request.stateId)
        assertNotNull(schedule.delay)
    }

    @Test
    fun `resolve does not redirect when already in advertised state`() {
        val futureAdvertised = System.currentTimeMillis() + 60_000
        val futurePublished = System.currentTimeMillis() + 120_000
        val attrs = JsonObject(mapOf(
            "advertised" to JsonPrimitive(futureAdvertised),
            "published" to JsonPrimitive(futurePublished),
        ))
        val request = beginTransition(stateId = "published")
        val item = fakeContentItem(workflowStateId = "advertised", attributes = attrs)

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("published", schedule.effectiveStateId)
        assertNotNull(schedule.delay)
    }

    @Test
    fun `resolve redirects to advertised but no delay when advertised epoch is in the past`() {
        val pastAdvertised = System.currentTimeMillis() - 60_000
        val futurePublished = System.currentTimeMillis() + 120_000
        val attrs = JsonObject(mapOf(
            "advertised" to JsonPrimitive(pastAdvertised),
            "published" to JsonPrimitive(futurePublished),
        ))
        val request = beginTransition(stateId = "published")
        val item = fakeContentItem(workflowStateId = "draft", attributes = attrs)

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("advertised", schedule.effectiveStateId)
        assertNull(schedule.delay)
    }

    @Test
    fun `resolve sets published delay when no advertised epoch and published is future`() {
        val futurePublished = System.currentTimeMillis() + 120_000
        val attrs = JsonObject(mapOf(
            "published" to JsonPrimitive(futurePublished),
        ))
        val request = beginTransition(stateId = "published")
        val item = fakeContentItem(workflowStateId = "draft", attributes = attrs)

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("published", schedule.effectiveStateId)
        assertNotNull(schedule.delay)
    }

    @Test
    fun `resolve with published epoch in past sets no delay`() {
        val pastPublished = System.currentTimeMillis() - 60_000
        val attrs = JsonObject(mapOf(
            "published" to JsonPrimitive(pastPublished),
        ))
        val request = beginTransition(stateId = "published")
        val item = fakeContentItem(workflowStateId = "draft", attributes = attrs)

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("published", schedule.effectiveStateId)
        assertNull(schedule.delay)
    }

    @Test
    fun `resolve with no epoch attributes and published target sets no delay`() {
        val request = beginTransition(stateId = "published")
        val item = fakeContentItem(workflowStateId = "draft", attributes = JsonObject(emptyMap()))

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("published", schedule.effectiveStateId)
        assertNull(schedule.delay)
    }

    @Test
    fun `resolve with null attributes and published target sets no delay`() {
        val request = beginTransition(stateId = "published")
        val item = fakeContentItem(workflowStateId = "draft", attributes = null)

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("published", schedule.effectiveStateId)
        assertNull(schedule.delay)
    }

    @Test
    fun `resolve does not redirect when advertised equals published`() {
        val epoch = System.currentTimeMillis() + 60_000
        val attrs = JsonObject(mapOf(
            "advertised" to JsonPrimitive(epoch),
            "published" to JsonPrimitive(epoch),
        ))
        val request = beginTransition(stateId = "published")
        val item = fakeContentItem(workflowStateId = "draft", attributes = attrs)

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("published", schedule.effectiveStateId)
        assertNotNull(schedule.delay)
    }

    @Test
    fun `resolve does not redirect when advertised is after published`() {
        val attrs = JsonObject(mapOf(
            "advertised" to JsonPrimitive(System.currentTimeMillis() + 120_000),
            "published" to JsonPrimitive(System.currentTimeMillis() + 60_000),
        ))
        val request = beginTransition(stateId = "published")
        val item = fakeContentItem(workflowStateId = "draft", attributes = attrs)

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("published", schedule.effectiveStateId)
    }

    @Test
    fun `resolve does not redirect when advertised is zero`() {
        val attrs = JsonObject(mapOf(
            "advertised" to JsonPrimitive(0L),
            "published" to JsonPrimitive(System.currentTimeMillis() + 60_000),
        ))
        val request = beginTransition(stateId = "published")
        val item = fakeContentItem(workflowStateId = "draft", attributes = attrs)

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("published", schedule.effectiveStateId)
    }

    @Test
    fun `resolve for advertised target with future advertised epoch sets delay`() {
        val futureAdvertised = System.currentTimeMillis() + 60_000
        val attrs = JsonObject(mapOf(
            "advertised" to JsonPrimitive(futureAdvertised),
        ))
        val request = beginTransition(stateId = "advertised")
        val item = fakeContentItem(workflowStateId = "draft", attributes = attrs)

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("advertised", schedule.effectiveStateId)
        assertNotNull(schedule.delay)
    }

    @Test
    fun `resolve preserves original request when no changes needed`() {
        val request = beginTransition(stateId = "draft")
        val item = fakeContentItem(workflowStateId = "pending")

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertTrue(schedule.request === request, "request should be the same instance when unchanged")
    }

    // ------------------------------------------------------------------
    // resolve(): explicit stateValid takes precedence over epoch logic
    // ------------------------------------------------------------------

    @Test
    fun `explicit stateValid in future takes precedence over epoch attributes`() {
        val futureValid = OffsetDateTime.now().plusHours(2)
        val attrs = JsonObject(mapOf(
            "published" to JsonPrimitive(System.currentTimeMillis() + 60_000),
        ))
        val request = beginTransition(stateId = "published", stateValid = futureValid)
        val item = fakeContentItem(workflowStateId = "draft", attributes = attrs)

        val schedule = TransitionScheduleResolver.resolve(request, item)

        assertEquals("published", schedule.effectiveStateId)
        assertNotNull(schedule.delay)
    }

    // ------------------------------------------------------------------
    // epochAttribute
    // ------------------------------------------------------------------

    @Test
    fun `epochAttribute parses numeric value`() {
        val attrs = JsonObject(mapOf("published" to JsonPrimitive(1745000000000L)))
        assertEquals(1745000000000L, attrs.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute parses string-encoded value`() {
        val attrs = Json.parseToJsonElement("""{"published": "1745000000000"}""")
        assertEquals(1745000000000L, attrs.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute returns null for missing key`() {
        val attrs = JsonObject(emptyMap())
        assertNull(attrs.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute returns null for null element`() {
        assertNull(null.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute returns null for non-object element`() {
        val arr = JsonArray(listOf(JsonPrimitive(42)))
        assertNull(arr.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute returns null for non-primitive value`() {
        val attrs = JsonObject(mapOf("published" to JsonObject(mapOf("v" to JsonPrimitive(1)))))
        assertNull(attrs.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute returns null for empty string`() {
        val attrs = Json.parseToJsonElement("""{"published": ""}""")
        assertNull(attrs.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute returns null for non-numeric string`() {
        val attrs = Json.parseToJsonElement("""{"published": "not-a-number"}""")
        assertNull(attrs.epochAttribute("published"))
    }

    @Test
    fun `epochAttribute parses negative string value`() {
        val attrs = Json.parseToJsonElement("""{"published": "-500"}""")
        assertEquals(-500L, attrs.epochAttribute("published"))
    }

    // ------------------------------------------------------------------
    // getEffectiveAdvertisedEpoch
    // ------------------------------------------------------------------

    @Test
    fun `getEffectiveAdvertisedEpoch returns null when advertised is null`() {
        assertNull(getEffectiveAdvertisedEpoch(null, 1000L))
    }

    @Test
    fun `getEffectiveAdvertisedEpoch returns null when advertised is zero`() {
        assertNull(getEffectiveAdvertisedEpoch(0L, 1000L))
    }

    @Test
    fun `getEffectiveAdvertisedEpoch returns null when advertised is negative`() {
        assertNull(getEffectiveAdvertisedEpoch(-1L, 1000L))
    }

    @Test
    fun `getEffectiveAdvertisedEpoch returns null when advertised equals published`() {
        assertNull(getEffectiveAdvertisedEpoch(1000L, 1000L))
    }

    @Test
    fun `getEffectiveAdvertisedEpoch returns null when advertised is after published`() {
        assertNull(getEffectiveAdvertisedEpoch(2000L, 1000L))
    }

    @Test
    fun `getEffectiveAdvertisedEpoch returns value when advertised is before published`() {
        assertEquals(500L, getEffectiveAdvertisedEpoch(500L, 1000L))
    }

    @Test
    fun `getEffectiveAdvertisedEpoch returns value when published is null`() {
        assertEquals(500L, getEffectiveAdvertisedEpoch(500L, null))
    }

    @Test
    fun `getEffectiveAdvertisedEpoch returns value for 1ms difference`() {
        assertEquals(999L, getEffectiveAdvertisedEpoch(999L, 1000L))
    }

    // ------------------------------------------------------------------
    // toFutureDelay
    // ------------------------------------------------------------------

    @Test
    fun `toFutureDelay returns null for past timestamp`() {
        val past = System.currentTimeMillis() - 60_000
        assertNull(past.toFutureDelay())
    }

    @Test
    fun `toFutureDelay returns value for future timestamp`() {
        val future = System.currentTimeMillis() + 60_000
        assertNotNull(future.toFutureDelay())
    }

    @Test
    fun `toFutureDelay returns null for timestamp at zero`() {
        assertNull(0L.toFutureDelay())
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun beginTransition(
        stateId: String,
        stateValid: OffsetDateTime? = null,
    ) = BeginTransitionInput(
        metadataId = UUID.random(),
        version = 1,
        stateId = stateId,
        status = "test",
        stateValid = stateValid,
    )

    private fun fakeContentItem(
        workflowStateId: String,
        attributes: JsonElement? = null,
    ): ContentItem = Metadata(
        name = "test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = workflowStateId,
        attributes = attributes,
        ready = OffsetDateTime.now(),
    )
}
