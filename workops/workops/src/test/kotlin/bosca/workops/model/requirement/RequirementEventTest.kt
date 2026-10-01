package bosca.workops.model.requirement

import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationEvent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequirementEventTest {

    private val sparseJson = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }
    private val explicitJson = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
        encodeDefaults = true
    }

    @Test
    fun `requirement event wire format rejects every missing required field`() {
        val fields = listOf(
            "requirementId" to JsonPrimitive(UUID.random().toString()),
            "commentId" to JsonPrimitive(13),
            "profileId" to JsonPrimitive(UUID.random().toString()),
        )

        fields.indices.forEach { missingIndex ->
            val partial = buildJsonObject {
                fields.take(missingIndex).forEach { (name, value) -> put(name, value) }
            }
            assertFailsWith<SerializationException> {
                sparseJson.decodeFromString<RequirementCommented>(partial.toString())
            }
        }
    }

    @Test
    fun `requirement event notification and delivery wire defaults are independently authorable`() {
        val event = RequirementCommented(UUID.random(), 13, UUID.random())
        val sparse = sparseJson.encodeToJsonElement(RequirementCommented.serializer(), event).jsonObject
        val withEvent = JsonObject(
            sparse + ("notificationEvent" to JsonPrimitive(NotificationEvent.REQUIREMENT_COMMENTED.name)),
        )
        assertEquals(event, sparseJson.decodeFromJsonElement(RequirementCommented.serializer(), withEvent))
        val withDelivery = JsonObject(
            sparse + (
                "delivery" to sparseJson.encodeToJsonElement(NotificationDelivery.serializer(), event.delivery)
            ),
        )
        assertEquals(event, sparseJson.decodeFromJsonElement(RequirementCommented.serializer(), withDelivery))
    }

    @Test
    fun `requirement comment derives and accepts matching delivery data`() {
        val requirementId = UUID.random()
        val profileId = UUID.random()
        val derived = RequirementCommented(requirementId, commentId = 9, profileId = profileId)
        assertTrue(derived.equals(derived))
        assertFalse(derived.equals(null))
        assertFalse(derived.equals(Any()))
        assertEquals(requirementId, derived.identityKey())
        assertEquals(NotificationEvent.REQUIREMENT_COMMENTED, derived.notificationEvent)
        assertEquals(requirementId, derived.delivery.requirementId)
        assertEquals(profileId, derived.delivery.actorProfileId)
        assertEquals(9, derived.delivery.commentId)

        val delivery = NotificationDelivery(
            id = UUID.random(),
            event = NotificationEvent.REQUIREMENT_COMMENTED,
            requirementId = requirementId,
            actorProfileId = profileId,
            commentId = 9,
        )
        val explicit = RequirementCommented(
            requirementId,
            commentId = 9,
            profileId = profileId,
            notificationEvent = NotificationEvent.REQUIREMENT_COMMENTED,
            delivery = delivery,
        )
        assertEquals(delivery, explicit.delivery)
        assertEquals(
            explicit,
            sparseJson.decodeFromString<RequirementCommented>(sparseJson.encodeToString(explicit)),
        )
        assertEquals(
            explicit,
            explicitJson.decodeFromString<RequirementCommented>(explicitJson.encodeToString(explicit)),
        )
    }

    @Test
    fun `requirement comment rejects mismatched event and delivery data`() {
        val requirementId = UUID.random()
        val profileId = UUID.random()
        assertFailsWith<IllegalArgumentException> {
            RequirementCommented(
                requirementId,
                commentId = 1,
                profileId = profileId,
                notificationEvent = NotificationEvent.TASK_COMMENTED,
            )
        }
        val event = RequirementCommented(requirementId, commentId = 1, profileId = profileId)
        assertFailsWith<IllegalArgumentException> {
            event.copy(requirementId = UUID.random())
        }
    }

    @Test
    fun `requirement event wire format restores omitted defaults and accepts explicit defaults`() {
        val event = RequirementCommented(UUID.random(), 13, UUID.random())

        assertEquals(event, sparseJson.decodeFromString<RequirementCommented>(sparseJson.encodeToString(event)))
        assertEquals(event, explicitJson.decodeFromString<RequirementCommented>(explicitJson.encodeToString(event)))
    }

    @Test
    fun `requirement comment defaults are independent when callers author the remaining envelope`() {
        val requirementId = UUID.random()
        val profileId = UUID.random()
        val delivery = NotificationDelivery(
            event = NotificationEvent.REQUIREMENT_COMMENTED,
            requirementId = requirementId,
            actorProfileId = profileId,
            commentId = 21,
        )

        assertEquals(
            NotificationEvent.REQUIREMENT_COMMENTED,
            RequirementCommented(
                requirementId,
                21,
                profileId,
                delivery = delivery,
            ).notificationEvent,
        )
        assertEquals(
            NotificationEvent.REQUIREMENT_COMMENTED,
            RequirementCommented(
                requirementId,
                21,
                profileId,
                NotificationEvent.REQUIREMENT_COMMENTED,
            ).delivery.event,
        )
    }
}
