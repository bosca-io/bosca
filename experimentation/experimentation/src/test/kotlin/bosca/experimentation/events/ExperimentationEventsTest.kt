package bosca.experimentation.events

import bosca.serialization.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExperimentationEventsTest {

    private val json = Json

    @Test
    fun `feature flag updated round-trips and identifies the flag`() {
        val event = FeatureFlagUpdated(UUID.random(), "checkout")

        val decoded = json.decodeFromString(
            FeatureFlagUpdated.serializer(),
            json.encodeToString(FeatureFlagUpdated.serializer(), event),
        )

        assertEquals(event.flagId, decoded.flagId)
        assertEquals("checkout", decoded.flagKey)
        assertEquals(event.flagId, decoded.identityKey())
    }

    @Test
    fun `feature flag updated requires both serialized fields`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(FeatureFlagUpdated.serializer(), "{}")
        }
    }
}
