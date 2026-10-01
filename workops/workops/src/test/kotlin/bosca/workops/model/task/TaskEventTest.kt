package bosca.workops.model.task

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

class TaskEventTest {

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
    fun `task event notification and delivery wire defaults are independently authorable`() {
        val taskId = UUID.random()
        val projectId = UUID.random()
        val profileId = UUID.random()
        val fromStatusId = UUID.random()
        val toStatusId = UUID.random()
        val events = listOf(
            TaskCreated(taskId, projectId, profileId) to TaskCreated.serializer(),
            TaskUpdated(taskId, projectId) to TaskUpdated.serializer(),
            TaskDeleted(taskId, projectId) to TaskDeleted.serializer(),
            TaskTransitioned(taskId, projectId, fromStatusId, toStatusId, UUID.random()) to TaskTransitioned.serializer(),
            TaskCommented(taskId, projectId, 9, profileId) to TaskCommented.serializer(),
            TaskSlaBreached(taskId, projectId, UUID.random()) to TaskSlaBreached.serializer(),
            TaskSlaAtRisk(taskId, projectId, UUID.random()) to TaskSlaAtRisk.serializer(),
        )

        events.forEach { (event, serializer) ->
            assertTrue(event.equals(event))
            assertFalse(event.equals(null))
            assertFalse(event.equals(Any()))
            @Suppress("UNCHECKED_CAST")
            assertEnvelopeDefaultsIndependent(
                event,
                serializer as KSerializer<TaskEvent>,
                event.notificationEvent,
                event.delivery,
            )
        }
    }

    @Test
    fun `task event wire formats reject every missing required field`() {
        val taskId = JsonPrimitive(UUID.random().toString())
        val projectId = JsonPrimitive(UUID.random().toString())
        val profileId = JsonPrimitive(UUID.random().toString())
        val statusId = JsonPrimitive(UUID.random().toString())

        assertRequiredFields<TaskCreated>(
            "taskId" to taskId,
            "projectId" to projectId,
            "reporterProfileId" to profileId,
        )
        assertRequiredFields<TaskUpdated>("taskId" to taskId, "projectId" to projectId)
        assertRequiredFields<TaskDeleted>("taskId" to taskId, "projectId" to projectId)
        assertRequiredFields<TaskTransitioned>(
            "taskId" to taskId,
            "projectId" to projectId,
            "fromStatusId" to statusId,
            "toStatusId" to JsonPrimitive(UUID.random().toString()),
            "transitionId" to JsonPrimitive(UUID.random().toString()),
        )
        assertRequiredFields<TaskCommented>(
            "taskId" to taskId,
            "projectId" to projectId,
            "commentId" to JsonPrimitive(5),
            "profileId" to profileId,
        )
        assertRequiredFields<TaskSlaBreached>(
            "taskId" to taskId,
            "projectId" to projectId,
            "goalId" to JsonPrimitive(UUID.random().toString()),
        )
        assertRequiredFields<TaskSlaAtRisk>(
            "taskId" to taskId,
            "projectId" to projectId,
            "goalId" to JsonPrimitive(UUID.random().toString()),
        )

    }

    @Test
    fun `task events derive matching notification deliveries from their domain fields`() {
        val taskId = UUID.random()
        val projectId = UUID.random()
        val profileId = UUID.random()
        val fromStatusId = UUID.random()
        val toStatusId = UUID.random()

        val created = TaskCreated(taskId, projectId, reporterProfileId = profileId)
        val updated = TaskUpdated(taskId, projectId)
        val deleted = TaskDeleted(taskId, projectId)
        val transitioned = TaskTransitioned(taskId, projectId, fromStatusId, toStatusId, UUID.random())
        val commented = TaskCommented(taskId, projectId, commentId = 42, profileId = profileId)
        val breached = TaskSlaBreached(taskId, projectId, goalId = UUID.random())
        val atRisk = TaskSlaAtRisk(taskId, projectId, goalId = UUID.random())

        listOf(created, updated, deleted, transitioned, commented, breached, atRisk).forEach { event ->
            assertEquals(taskId, event.identityKey())
            assertEquals(taskId, event.delivery.taskId)
            assertEquals(projectId, event.delivery.projectId)
            assertEquals(event.notificationEvent, event.delivery.event)
        }
        assertEquals(profileId, created.reporterProfileId)
        assertEquals(null, created.assigneeProfileId)
        assertEquals(false, updated.assigneeChanged)
        assertEquals(null, transitioned.resolutionId)
        assertEquals(emptySet(), commented.mentionedProfileIds)
    }

    @Test
    fun `task events accept explicit matching optional fields and delivery identifiers`() {
        val taskId = UUID.random()
        val projectId = UUID.random()
        val actorId = UUID.random()
        val assigneeId = UUID.random()
        val fromStatusId = UUID.random()
        val toStatusId = UUID.random()
        val transitionId = UUID.random()
        val resolutionId = UUID.random()
        val mentioned = setOf(UUID.random(), UUID.random())

        fun delivery(
            id: UUID,
            event: NotificationEvent,
            actorProfileId: UUID? = null,
            commentId: Long? = null,
            mentionedProfileIds: Set<UUID> = emptySet(),
            assigneeProfileId: UUID? = null,
            assigneeChanged: Boolean = false,
            fromStatus: UUID? = null,
            toStatus: UUID? = null,
        ) = NotificationDelivery(
            id = id,
            event = event,
            taskId = taskId,
            projectId = projectId,
            actorProfileId = actorProfileId,
            mentionedProfileIds = mentionedProfileIds,
            commentId = commentId,
            assigneeProfileId = assigneeProfileId,
            assigneeChanged = assigneeChanged,
            fromStatusId = fromStatus,
            toStatusId = toStatus,
        )

        val createdDelivery = delivery(UUID.random(), NotificationEvent.TASK_CREATED, assigneeProfileId = assigneeId)
        val created = TaskCreated(
            taskId,
            projectId,
            reporterProfileId = actorId,
            assigneeProfileId = assigneeId,
            notificationEvent = NotificationEvent.TASK_CREATED,
            delivery = createdDelivery,
        )
        val updatedDelivery = delivery(
            UUID.random(),
            NotificationEvent.TASK_UPDATED,
            assigneeProfileId = assigneeId,
            assigneeChanged = true,
        )
        val updated = TaskUpdated(
            taskId,
            projectId,
            assigneeProfileId = assigneeId,
            assigneeChanged = true,
            notificationEvent = NotificationEvent.TASK_UPDATED,
            delivery = updatedDelivery,
        )
        val deletedDelivery = delivery(UUID.random(), NotificationEvent.TASK_DELETED)
        val deleted = TaskDeleted(
            taskId,
            projectId,
            notificationEvent = NotificationEvent.TASK_DELETED,
            delivery = deletedDelivery,
        )
        val transitionedDelivery = delivery(
            UUID.random(),
            NotificationEvent.TASK_TRANSITIONED,
            fromStatus = fromStatusId,
            toStatus = toStatusId,
        )
        val transitioned = TaskTransitioned(
            taskId,
            projectId,
            fromStatusId,
            toStatusId,
            transitionId,
            resolutionId = resolutionId,
            notificationEvent = NotificationEvent.TASK_TRANSITIONED,
            delivery = transitionedDelivery,
        )
        val commentedDelivery = delivery(
            UUID.random(),
            NotificationEvent.TASK_COMMENTED,
            actorProfileId = actorId,
            commentId = 7,
            mentionedProfileIds = mentioned,
        )
        val commented = TaskCommented(
            taskId,
            projectId,
            commentId = 7,
            profileId = actorId,
            mentionedProfileIds = mentioned,
            notificationEvent = NotificationEvent.TASK_COMMENTED,
            delivery = commentedDelivery,
        )
        val breachedDelivery = delivery(UUID.random(), NotificationEvent.SLA_BREACHED)
        val breached = TaskSlaBreached(
            taskId,
            projectId,
            goalId = UUID.random(),
            notificationEvent = NotificationEvent.SLA_BREACHED,
            delivery = breachedDelivery,
        )
        val atRiskDelivery = delivery(UUID.random(), NotificationEvent.SLA_AT_RISK)
        val atRisk = TaskSlaAtRisk(
            taskId,
            projectId,
            goalId = UUID.random(),
            notificationEvent = NotificationEvent.SLA_AT_RISK,
            delivery = atRiskDelivery,
        )

        assertEquals(createdDelivery, created.delivery)
        assertEquals(updatedDelivery, updated.delivery)
        assertEquals(deletedDelivery, deleted.delivery)
        assertEquals(resolutionId, transitioned.resolutionId)
        assertEquals(transitionedDelivery, transitioned.delivery)
        assertEquals(commentedDelivery, commented.delivery)
        assertEquals(breachedDelivery, breached.delivery)
        assertEquals(atRiskDelivery, atRisk.delivery)
        listOf(created, updated, deleted, transitioned, commented, breached, atRisk).forEach {
            assertWireRoundTrips(it)
        }
    }

    @Test
    fun `task events reject mismatched notification types and stale copied deliveries`() {
        val taskId = UUID.random()
        val projectId = UUID.random()
        assertFailsWith<IllegalArgumentException> {
            TaskUpdated(taskId, projectId, notificationEvent = NotificationEvent.TASK_CREATED)
        }

        val created = TaskCreated(taskId, projectId, reporterProfileId = UUID.random())
        assertFailsWith<IllegalArgumentException> {
            created.copy(taskId = UUID.random())
        }
    }

    @Test
    fun `task event wire format restores omitted defaults and accepts explicit defaults`() {
        val taskId = UUID.random()
        val projectId = UUID.random()
        val profileId = UUID.random()
        assertWireRoundTrips(TaskCreated(taskId, projectId, profileId))
        assertWireRoundTrips(TaskUpdated(taskId, projectId))
        assertWireRoundTrips(TaskDeleted(taskId, projectId))
        assertWireRoundTrips(
            TaskTransitioned(taskId, projectId, UUID.random(), UUID.random(), UUID.random()),
        )
        assertWireRoundTrips(TaskCommented(taskId, projectId, 17, profileId))
        assertWireRoundTrips(TaskSlaBreached(taskId, projectId, UUID.random()))
        assertWireRoundTrips(TaskSlaAtRisk(taskId, projectId, UUID.random()))
    }

    @Test
    fun `task event defaults are independent when callers author the remaining envelope`() {
        val taskId = UUID.random()
        val projectId = UUID.random()
        val profileId = UUID.random()
        val assigneeId = UUID.random()
        val fromStatusId = UUID.random()
        val toStatusId = UUID.random()
        val transitionId = UUID.random()
        val resolutionId = UUID.random()
        val mentioned = setOf(UUID.random())

        fun delivery(
            event: NotificationEvent,
            assignee: UUID? = null,
            changed: Boolean = false,
            actor: UUID? = null,
            commentId: Long? = null,
            mentions: Set<UUID> = emptySet(),
            from: UUID? = null,
            to: UUID? = null,
        ) = NotificationDelivery(
            event = event,
            taskId = taskId,
            projectId = projectId,
            assigneeProfileId = assignee,
            assigneeChanged = changed,
            actorProfileId = actor,
            commentId = commentId,
            mentionedProfileIds = mentions,
            fromStatusId = from,
            toStatusId = to,
        )

        assertEquals(
            null,
            TaskCreated(
                taskId,
                projectId,
                profileId,
                notificationEvent = NotificationEvent.TASK_CREATED,
                delivery = delivery(NotificationEvent.TASK_CREATED),
            ).assigneeProfileId,
        )
        assertEquals(
            NotificationEvent.TASK_CREATED,
            TaskCreated(
                taskId,
                projectId,
                profileId,
                assigneeId,
                delivery = delivery(NotificationEvent.TASK_CREATED, assignee = assigneeId),
            ).notificationEvent,
        )
        assertEquals(
            assigneeId,
            TaskCreated(
                taskId,
                projectId,
                profileId,
                assigneeId,
                NotificationEvent.TASK_CREATED,
            ).delivery.assigneeProfileId,
        )

        assertEquals(
            null,
            TaskUpdated(
                taskId,
                projectId,
                assigneeChanged = true,
                notificationEvent = NotificationEvent.TASK_UPDATED,
                delivery = delivery(NotificationEvent.TASK_UPDATED, changed = true),
            ).assigneeProfileId,
        )
        assertEquals(
            false,
            TaskUpdated(
                taskId,
                projectId,
                assigneeId,
                notificationEvent = NotificationEvent.TASK_UPDATED,
                delivery = delivery(NotificationEvent.TASK_UPDATED, assignee = assigneeId),
            ).assigneeChanged,
        )
        assertEquals(
            true,
            TaskUpdated(
                taskId,
                projectId,
                assigneeId,
                true,
                delivery = delivery(NotificationEvent.TASK_UPDATED, assigneeId, true),
            ).delivery.assigneeChanged,
        )
        assertEquals(
            assigneeId,
            TaskUpdated(taskId, projectId, assigneeId, true, NotificationEvent.TASK_UPDATED).delivery.assigneeProfileId,
        )

        assertEquals(
            NotificationEvent.TASK_DELETED,
            TaskDeleted(
                taskId,
                projectId,
                delivery = delivery(NotificationEvent.TASK_DELETED),
            ).notificationEvent,
        )
        assertEquals(
            NotificationEvent.TASK_DELETED,
            TaskDeleted(taskId, projectId, NotificationEvent.TASK_DELETED).delivery.event,
        )

        assertEquals(
            null,
            TaskTransitioned(
                taskId,
                projectId,
                fromStatusId,
                toStatusId,
                transitionId,
                notificationEvent = NotificationEvent.TASK_TRANSITIONED,
                delivery = delivery(NotificationEvent.TASK_TRANSITIONED, from = fromStatusId, to = toStatusId),
            ).resolutionId,
        )
        assertEquals(
            NotificationEvent.TASK_TRANSITIONED,
            TaskTransitioned(
                taskId,
                projectId,
                fromStatusId,
                toStatusId,
                transitionId,
                resolutionId,
                delivery = delivery(NotificationEvent.TASK_TRANSITIONED, from = fromStatusId, to = toStatusId),
            ).notificationEvent,
        )
        assertEquals(
            fromStatusId,
            TaskTransitioned(
                taskId,
                projectId,
                fromStatusId,
                toStatusId,
                transitionId,
                resolutionId,
                NotificationEvent.TASK_TRANSITIONED,
            ).delivery.fromStatusId,
        )

        assertEquals(
            emptySet(),
            TaskCommented(
                taskId,
                projectId,
                9,
                profileId,
                notificationEvent = NotificationEvent.TASK_COMMENTED,
                delivery = delivery(NotificationEvent.TASK_COMMENTED, actor = profileId, commentId = 9),
            ).mentionedProfileIds,
        )
        assertEquals(
            NotificationEvent.TASK_COMMENTED,
            TaskCommented(
                taskId,
                projectId,
                9,
                profileId,
                mentioned,
                delivery = delivery(
                    NotificationEvent.TASK_COMMENTED,
                    actor = profileId,
                    commentId = 9,
                    mentions = mentioned,
                ),
            ).notificationEvent,
        )
        assertEquals(
            mentioned,
            TaskCommented(
                taskId,
                projectId,
                9,
                profileId,
                mentioned,
                NotificationEvent.TASK_COMMENTED,
            ).delivery.mentionedProfileIds,
        )

        val goalId = UUID.random()
        assertEquals(
            NotificationEvent.SLA_BREACHED,
            TaskSlaBreached(
                taskId,
                projectId,
                goalId,
                delivery = delivery(NotificationEvent.SLA_BREACHED),
            ).notificationEvent,
        )
        assertEquals(
            NotificationEvent.SLA_BREACHED,
            TaskSlaBreached(taskId, projectId, goalId, NotificationEvent.SLA_BREACHED).delivery.event,
        )
        assertEquals(
            NotificationEvent.SLA_AT_RISK,
            TaskSlaAtRisk(
                taskId,
                projectId,
                goalId,
                delivery = delivery(NotificationEvent.SLA_AT_RISK),
            ).notificationEvent,
        )
        assertEquals(
            NotificationEvent.SLA_AT_RISK,
            TaskSlaAtRisk(taskId, projectId, goalId, NotificationEvent.SLA_AT_RISK).delivery.event,
        )
    }
}
