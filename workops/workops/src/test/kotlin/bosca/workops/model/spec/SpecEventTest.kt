package bosca.workops.model.spec

import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationEvent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

class SpecEventTest {

    private val sparseJson = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }
    private val explicitJson = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
        encodeDefaults = true
    }

    private inline fun <reified T> assertWireRoundTrips(value: T) {
        assertEquals(value, sparseJson.decodeFromString<T>(sparseJson.encodeToString(value)))
        assertEquals(value, explicitJson.decodeFromString<T>(explicitJson.encodeToString(value)))
    }

    private inline fun <reified T> assertRequiredFields(vararg fields: Pair<String, JsonElement>) {
        fields.indices.forEach { missingIndex ->
            val partial = buildJsonObject {
                fields.take(missingIndex).forEach { (name, value) -> put(name, value) }
            }
            assertFailsWith<SerializationException> {
                sparseJson.decodeFromString<T>(partial.toString())
            }
        }
    }

    private fun <T> assertEnvelopeDefaultsIndependent(
        value: T,
        serializer: KSerializer<T>,
        event: NotificationEvent,
        delivery: NotificationDelivery,
    ) {
        val sparse = sparseJson.encodeToJsonElement(serializer, value).jsonObject
        val withEvent = JsonObject(sparse + ("notificationEvent" to JsonPrimitive(event.name)))
        assertEquals(value, sparseJson.decodeFromJsonElement(serializer, withEvent))
        val withDelivery = JsonObject(
            sparse + ("delivery" to sparseJson.encodeToJsonElement(NotificationDelivery.serializer(), delivery)),
        )
        assertEquals(value, sparseJson.decodeFromJsonElement(serializer, withDelivery))
    }

    @Test
    fun `spec event notification and delivery wire defaults are independently authorable`() {
        val specId = UUID.random()
        val profileId = UUID.random()
        val events = listOf(
            SpecCreated(specId, UUID.random(), profileId) to SpecCreated.serializer(),
            SpecUpdated(specId) to SpecUpdated.serializer(),
            SpecDeleted(specId) to SpecDeleted.serializer(),
            SpecTransitioned(specId, UUID.random(), UUID.random(), UUID.random()) to SpecTransitioned.serializer(),
            SpecCommented(specId, 9, profileId) to SpecCommented.serializer(),
            SpecTasksGenerated(specId, GenerationSource.MANUAL, 3) to SpecTasksGenerated.serializer(),
        )

        events.forEach { (event, serializer) ->
            assertTrue(event.equals(event))
            assertFalse(event.equals(null))
            assertFalse(event.equals(Any()))
            @Suppress("UNCHECKED_CAST")
            assertEnvelopeDefaultsIndependent(
                event,
                serializer as KSerializer<SpecEvent>,
                event.notificationEvent,
                event.delivery,
            )
        }
    }

    @Test
    fun `spec event wire formats reject every missing required field`() {
        val specId = JsonPrimitive(UUID.random().toString())
        val profileId = JsonPrimitive(UUID.random().toString())
        val statusId = JsonPrimitive(UUID.random().toString())

        assertRequiredFields<SpecCreated>(
            "specId" to specId,
            "projectId" to JsonNull,
            "ownerProfileId" to profileId,
        )
        assertRequiredFields<SpecUpdated>("specId" to specId)
        assertRequiredFields<SpecDeleted>("specId" to specId)
        assertRequiredFields<SpecTransitioned>(
            "specId" to specId,
            "fromStatusId" to statusId,
            "toStatusId" to JsonPrimitive(UUID.random().toString()),
            "transitionId" to JsonPrimitive(UUID.random().toString()),
        )
        assertRequiredFields<SpecCommented>(
            "specId" to specId,
            "commentId" to JsonPrimitive(9),
            "profileId" to profileId,
        )
        assertRequiredFields<SpecTasksGenerated>(
            "specId" to specId,
            "source" to JsonPrimitive(GenerationSource.MANUAL.name),
            "taskCount" to JsonPrimitive(3),
        )
    }

    @Test
    fun `created spec carries the typed notification event`() {
        val event = SpecCreated(
            specId = UUID.random(),
            projectId = UUID.random(),
            ownerProfileId = UUID.random(),
        )

        assertEquals(NotificationEvent.SPEC_CREATED, event.notificationEvent)
        assertEquals(NotificationEvent.SPEC_CREATED, event.delivery.event)
    }

    @Test
    fun `spec events reject a mismatched notification type or stale copied delivery`() {
        val specId = UUID.random()
        val projectId = UUID.random()
        assertFailsWith<IllegalArgumentException> {
            SpecCreated(
                specId,
                projectId,
                UUID.random(),
                notificationEvent = NotificationEvent.SPEC_UPDATED,
            )
        }
        val event = SpecCreated(specId, projectId, UUID.random())
        assertFailsWith<IllegalArgumentException> {
            event.copy(specId = UUID.random())
        }
        assertFailsWith<IllegalArgumentException> {
            event.copy(
                delivery = NotificationDelivery(
                    event = NotificationEvent.SPEC_CREATED,
                    specId = specId,
                    projectId = UUID.random(),
                ),
            )
        }
    }

    @Test
    fun `generated tasks carry notification delivery`() {
        val specId = UUID.random()
        val event = SpecTasksGenerated(specId, GenerationSource.MANUAL, 3)

        assertEquals(NotificationEvent.SPEC_TASKS_GENERATED, event.delivery.event)
        assertEquals(specId, event.delivery.specId)
    }

    @Test
    fun `every spec event derives its identity and matching delivery`() {
        val specId = UUID.random()
        val fromStatusId = UUID.random()
        val toStatusId = UUID.random()
        val actorId = UUID.random()
        val events = listOf(
            SpecCreated(specId, projectId = null, ownerProfileId = UUID.random()),
            SpecUpdated(specId),
            SpecDeleted(specId),
            SpecTransitioned(specId, fromStatusId, toStatusId, transitionId = UUID.random()),
            SpecCommented(specId, commentId = 11, profileId = actorId),
            SpecTasksGenerated(specId, GenerationSource.KIT, taskCount = 4),
        )

        events.forEach { event ->
            assertEquals(specId, event.identityKey())
            assertEquals(specId, event.delivery.specId)
            assertEquals(event.notificationEvent, event.delivery.event)
        }
        assertEquals(fromStatusId, events[3].delivery.fromStatusId)
        assertEquals(toStatusId, events[3].delivery.toStatusId)
        assertEquals(actorId, events[4].delivery.actorProfileId)
    }

    @Test
    fun `every spec event accepts an explicit matching delivery`() {
        val specId = UUID.random()
        val projectId = UUID.random()
        val ownerId = UUID.random()
        val actorId = UUID.random()
        val fromStatusId = UUID.random()
        val toStatusId = UUID.random()
        fun delivery(
            event: NotificationEvent,
            project: UUID? = null,
            actor: UUID? = null,
            commentId: Long? = null,
            from: UUID? = null,
            to: UUID? = null,
        ) = NotificationDelivery(
            id = UUID.random(),
            event = event,
            specId = specId,
            projectId = project,
            actorProfileId = actor,
            commentId = commentId,
            fromStatusId = from,
            toStatusId = to,
        )

        val createdDelivery = delivery(NotificationEvent.SPEC_CREATED, project = projectId)
        val updatedDelivery = delivery(NotificationEvent.SPEC_UPDATED)
        val deletedDelivery = delivery(NotificationEvent.SPEC_DELETED)
        val transitionedDelivery = delivery(
            NotificationEvent.SPEC_TRANSITIONED,
            from = fromStatusId,
            to = toStatusId,
        )
        val commentedDelivery = delivery(NotificationEvent.SPEC_COMMENTED, actor = actorId, commentId = 12)
        val generatedDelivery = delivery(NotificationEvent.SPEC_TASKS_GENERATED)

        assertEquals(
            createdDelivery,
            SpecCreated(
                specId,
                projectId,
                ownerId,
                NotificationEvent.SPEC_CREATED,
                createdDelivery,
            ).delivery,
        )
        assertEquals(
            updatedDelivery,
            SpecUpdated(specId, NotificationEvent.SPEC_UPDATED, updatedDelivery).delivery,
        )
        assertEquals(
            deletedDelivery,
            SpecDeleted(specId, NotificationEvent.SPEC_DELETED, deletedDelivery).delivery,
        )
        assertEquals(
            transitionedDelivery,
            SpecTransitioned(
                specId,
                fromStatusId,
                toStatusId,
                UUID.random(),
                NotificationEvent.SPEC_TRANSITIONED,
                transitionedDelivery,
            ).delivery,
        )
        assertEquals(
            commentedDelivery,
            SpecCommented(specId, 12, actorId, NotificationEvent.SPEC_COMMENTED, commentedDelivery).delivery,
        )
        assertEquals(
            generatedDelivery,
            SpecTasksGenerated(
                specId,
                GenerationSource.MANUAL,
                2,
                NotificationEvent.SPEC_TASKS_GENERATED,
                generatedDelivery,
            ).delivery,
        )

        assertWireRoundTrips(SpecCreated(specId, projectId, ownerId, NotificationEvent.SPEC_CREATED, createdDelivery))
        assertWireRoundTrips(SpecUpdated(specId, NotificationEvent.SPEC_UPDATED, updatedDelivery))
        assertWireRoundTrips(SpecDeleted(specId, NotificationEvent.SPEC_DELETED, deletedDelivery))
        assertWireRoundTrips(
            SpecTransitioned(
                specId,
                fromStatusId,
                toStatusId,
                UUID.random(),
                NotificationEvent.SPEC_TRANSITIONED,
                transitionedDelivery,
            ),
        )
        assertWireRoundTrips(
            SpecCommented(specId, 12, actorId, NotificationEvent.SPEC_COMMENTED, commentedDelivery),
        )
        assertWireRoundTrips(
            SpecTasksGenerated(
                specId,
                GenerationSource.MANUAL,
                2,
                NotificationEvent.SPEC_TASKS_GENERATED,
                generatedDelivery,
            ),
        )
    }

    @Test
    fun `spec event wire format restores omitted defaults and accepts explicit defaults`() {
        val specId = UUID.random()
        val fromStatusId = UUID.random()
        val toStatusId = UUID.random()
        val profileId = UUID.random()
        assertWireRoundTrips(SpecCreated(specId, UUID.random(), profileId))
        assertWireRoundTrips(SpecUpdated(specId))
        assertWireRoundTrips(SpecDeleted(specId))
        assertWireRoundTrips(SpecTransitioned(specId, fromStatusId, toStatusId, UUID.random()))
        assertWireRoundTrips(SpecCommented(specId, 21, profileId))
        assertWireRoundTrips(SpecTasksGenerated(specId, GenerationSource.MANUAL, 3))
    }

    @Test
    fun `spec event defaults are independent when callers author the remaining envelope`() {
        val specId = UUID.random()
        val projectId = UUID.random()
        val ownerId = UUID.random()
        val profileId = UUID.random()
        val fromStatusId = UUID.random()
        val toStatusId = UUID.random()

        fun delivery(
            event: NotificationEvent,
            actorId: UUID? = null,
            commentId: Long? = null,
            fromStatus: UUID? = null,
            toStatus: UUID? = null,
            project: UUID? = null,
        ) = NotificationDelivery(
            event = event,
            specId = specId,
            projectId = project,
            actorProfileId = actorId,
            commentId = commentId,
            fromStatusId = fromStatus,
            toStatusId = toStatus,
        )

        assertEquals(
            NotificationEvent.SPEC_CREATED,
            SpecCreated(
                specId,
                projectId,
                ownerId,
                delivery = delivery(NotificationEvent.SPEC_CREATED, project = projectId),
            ).notificationEvent,
        )
        assertEquals(
            projectId,
            SpecCreated(specId, projectId, ownerId, NotificationEvent.SPEC_CREATED).delivery.projectId,
        )
        assertEquals(
            NotificationEvent.SPEC_UPDATED,
            SpecUpdated(specId, delivery = delivery(NotificationEvent.SPEC_UPDATED)).notificationEvent,
        )
        assertEquals(
            NotificationEvent.SPEC_UPDATED,
            SpecUpdated(specId, NotificationEvent.SPEC_UPDATED).delivery.event,
        )
        assertEquals(
            NotificationEvent.SPEC_DELETED,
            SpecDeleted(specId, delivery = delivery(NotificationEvent.SPEC_DELETED)).notificationEvent,
        )
        assertEquals(
            NotificationEvent.SPEC_DELETED,
            SpecDeleted(specId, NotificationEvent.SPEC_DELETED).delivery.event,
        )
        assertEquals(
            NotificationEvent.SPEC_TRANSITIONED,
            SpecTransitioned(
                specId,
                fromStatusId,
                toStatusId,
                UUID.random(),
                delivery = delivery(
                    NotificationEvent.SPEC_TRANSITIONED,
                    fromStatus = fromStatusId,
                    toStatus = toStatusId,
                ),
            ).notificationEvent,
        )
        assertEquals(
            fromStatusId,
            SpecTransitioned(
                specId,
                fromStatusId,
                toStatusId,
                UUID.random(),
                NotificationEvent.SPEC_TRANSITIONED,
            ).delivery.fromStatusId,
        )
        assertEquals(
            NotificationEvent.SPEC_COMMENTED,
            SpecCommented(
                specId,
                27,
                profileId,
                delivery = delivery(NotificationEvent.SPEC_COMMENTED, profileId, 27),
            ).notificationEvent,
        )
        assertEquals(
            profileId,
            SpecCommented(specId, 27, profileId, NotificationEvent.SPEC_COMMENTED).delivery.actorProfileId,
        )
        assertEquals(
            NotificationEvent.SPEC_TASKS_GENERATED,
            SpecTasksGenerated(
                specId,
                GenerationSource.MANUAL,
                3,
                delivery = delivery(NotificationEvent.SPEC_TASKS_GENERATED),
            ).notificationEvent,
        )
        assertEquals(
            NotificationEvent.SPEC_TASKS_GENERATED,
            SpecTasksGenerated(
                specId,
                GenerationSource.MANUAL,
                3,
                NotificationEvent.SPEC_TASKS_GENERATED,
            ).delivery.event,
        )
    }
}
