package bosca.workops.service

import bosca.di.annotation.ProviderName
import bosca.pipelines.service.PipelineService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.JobQueue
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.automation.AutomationEmailRequested
import bosca.workops.model.automation.dispatch
import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.NotificationEvent
import bosca.workops.model.notification.NotificationOutboxEntry
import bosca.workops.model.notification.NotificationOutboxAction
import bosca.workops.model.notification.NotificationPreference
import bosca.workops.model.notification.NotificationRecipient
import bosca.workops.model.notification.TypedNotificationScheme
import bosca.workops.model.notification.WorkOpsEmailRequested
import bosca.workops.model.notification.WorkOpsChannelRequested
import bosca.workops.model.notification.dispatch
import bosca.workops.model.notification.prepareDeliveryJob
import bosca.workops.model.task.Task
import bosca.workops.repository.NotificationOutboxRepository
import bosca.workops.repository.NotificationPreferenceRepository
import bosca.workops.repository.NotificationRepository
import bosca.workops.repository.NotificationSchemeRepository
import bosca.workops.repository.NotificationSubscriptionRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.TaskWatcherRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal val DEFAULT_NOTIFICATION_CHANNELS = setOf(
    NotificationChannel.IN_APP,
    NotificationChannel.EMAIL,
)

@ServiceImplementation
class NotificationSchemeServiceImpl(
    private val repository: NotificationSchemeRepository,
    private val json: Json,
) : NotificationSchemeService {

    companion object {
        private val log = LoggerFactory.getLogger(NotificationSchemeServiceImpl::class.java)
    }

    override suspend fun list() = repository.listAll()

    override suspend fun getById(id: UUID) = repository.getById(id)

    override suspend fun typed(id: UUID): TypedNotificationScheme? {
        val row = repository.getById(id) ?: return null
        val obj = row.eventRecipients as? JsonObject ?: JsonObject(emptyMap())
        val map = mutableMapOf<String, List<NotificationRecipient>>()
        for ((key, value) in obj) {
            map[key] = runCatching {
                json.decodeFromJsonElement(ListSerializer(NotificationRecipient.serializer()), value)
            }.getOrElse { ex ->
                log.error("Notification scheme {}: corrupt recipient data for event '{}', returning empty recipients: {}", id, key, ex.message, ex)
                emptyList()
            }
        }
        return TypedNotificationScheme(
            id = row.id, name = row.name, description = row.description,
            recipientsByEvent = map, version = row.version,
        )
    }
}

@ServiceImplementation
class NotificationPreferenceServiceImpl(
    private val repository: NotificationPreferenceRepository,
    private val json: Json,
) : NotificationPreferenceService {

    companion object {
        private val log = LoggerFactory.getLogger(NotificationPreferenceServiceImpl::class.java)
    }

    override suspend fun get(profileId: UUID) = repository.getByProfile(profileId)

    override suspend fun upsert(profileId: UUID, input: NotificationPreferenceInput): NotificationPreference {
        val encoded = buildJsonObject {
            for ((event, channels) in input.eventChannels) {
                put(event, json.encodeToJsonElement(SetSerializer(NotificationChannel.serializer()), channels))
            }
        }
        return repository.upsert(
            bosca.workops.repository.NotificationPreferenceUpsertParams(
                profileId = profileId,
                eventChannels = json.encodeToString(JsonObject.serializer(), encoded),
                watchAuthored = input.watchAuthored,
                watchCommented = input.watchCommented,
                dailyDigest = input.dailyDigest,
                dndStartLocal = input.dndStartLocal,
                dndEndLocal = input.dndEndLocal,
                mutedTaskIds = input.mutedTaskIds,
                mutedProjectIds = input.mutedProjectIds,
            )
        )
    }

    override suspend fun decodedChannels(
        pref: NotificationPreference,
        event: String,
    ): Set<NotificationChannel> {
        val obj = pref.eventChannels as? JsonObject ?: return DEFAULT_NOTIFICATION_CHANNELS
        val list = obj[event] ?: return DEFAULT_NOTIFICATION_CHANNELS
        return runCatching {
            json.decodeFromJsonElement(SetSerializer(NotificationChannel.serializer()), list)
        }.getOrElse { ex ->
            log.warn("Notification preference for profile {}: failed to decode channels for event '{}': {}", pref.profileId, event, ex.message)
            DEFAULT_NOTIFICATION_CHANNELS
        }
    }

}

@ServiceImplementation
class TaskWatcherServiceImpl(
    private val repository: TaskWatcherRepository,
    private val taskService: TaskService,
) : TaskWatcherService {
    override suspend fun list(taskId: UUID) = repository.listByTask(taskId)
    override suspend fun listForProfile(profileId: UUID) = repository.listByProfile(profileId)
    override suspend fun add(taskId: UUID, profileId: UUID): bosca.workops.model.notification.TaskWatcher? {
        val task = taskService.getById(taskId) ?: throw WorkOpsNotFoundException("Task", taskId.toString())
        return repository.add(taskId, profileId)?.also {
            NotificationDeliveryRequested(
                NotificationDelivery(
                    event = NotificationEvent.WATCH_ADDED,
                    taskId = task.id,
                    projectId = task.projectId,
                    actorProfileId = profileId,
                ),
            ).dispatch()
        }
    }

    override suspend fun remove(taskId: UUID, profileId: UUID) {
        val task = taskService.getById(taskId) ?: throw WorkOpsNotFoundException("Task", taskId.toString())
        if (repository.remove(taskId, profileId) > 0) {
            NotificationDeliveryRequested(
                NotificationDelivery(
                    event = NotificationEvent.WATCH_REMOVED,
                    taskId = task.id,
                    projectId = task.projectId,
                    actorProfileId = profileId,
                ),
            ).dispatch()
        }
    }
}

@ServiceImplementation
class NotificationInboxServiceImpl(
    private val repository: NotificationRepository,
) : NotificationInboxService {
    override suspend fun add(
        profileId: UUID,
        event: String,
        taskId: UUID?,
        projectId: UUID?,
        actorProfileId: UUID?,
        body: String,
        link: String?,
    ) = repository.add(profileId, event, taskId, projectId, actorProfileId, body, link)

    override suspend fun addOnce(
        sourceId: UUID,
        profileId: UUID,
        event: String,
        taskId: UUID?,
        projectId: UUID?,
        actorProfileId: UUID?,
        body: String,
        link: String?,
    ) = repository.addOnce(sourceId, profileId, event, taskId, projectId, actorProfileId, body, link)
        ?: requireNotNull(repository.getBySource(sourceId, event, profileId)) {
            "Notification $sourceId/$event for profile $profileId disappeared after an idempotency conflict"
        }

    override suspend fun list(profileId: UUID, offset: Long, limit: Int) =
        repository.listByProfile(profileId, offset, limit.coerceIn(1, 200))

    override suspend fun listUnread(profileId: UUID, offset: Long, limit: Int) =
        repository.listUnread(profileId, offset, limit.coerceIn(1, 200))

    override suspend fun unreadCount(profileId: UUID) = repository.countUnread(profileId)
    override suspend fun markRead(id: UUID, profileId: UUID) = repository.markRead(id, profileId)
    override suspend fun markAllRead(profileId: UUID) = repository.markAllRead(profileId)
}

@ServiceImplementation
class NotificationOutboxServiceImpl(
    private val repository: NotificationOutboxRepository,
    @ProviderName("workops")
    private val queue: JobQueue,
    private val deliveryService: NotificationChannelDeliveryService,
) : NotificationOutboxService {
    override suspend fun enqueue(channel: NotificationChannel, target: String, payload: String) {
        enqueueOnce(UUID.random(), "OUTBOX", channel, target, payload)
    }

    override suspend fun enqueue(channel: String, target: String, payload: String) {
        NotificationChannel.entries.firstOrNull { it.name.equals(channel, ignoreCase = true) }?.let { typed ->
            enqueue(typed, target, payload)
            return
        }
        val action = NotificationOutboxAction.entries.firstOrNull { it.name.equals(channel, ignoreCase = true) }
            ?: error("Unsupported notification outbox action '$channel'")
        repository.addRaw(UUID.random(), action.name, action, target, payload)
    }

    override suspend fun enqueueOnce(
        sourceId: UUID,
        event: String,
        channel: NotificationChannel,
        target: String,
        payload: String,
        availableAt: OffsetDateTime?,
        digest: Boolean,
    ): NotificationOutboxEntry {
        require(channel != NotificationChannel.IN_APP) { "IN_APP notifications do not use the outbox" }
        val entry = repository.addOnce(sourceId, event, channel, target, payload, availableAt, digest)
            ?: requireNotNull(repository.getBySource(sourceId, event, channel, target)) {
                "Notification outbox row $sourceId/$event/$channel/$target disappeared after an idempotency conflict"
            }
        val availableAt = entry.availableAt
        if (!entry.digest && (availableAt == null || !availableAt.isAfter(OffsetDateTime.now()))) {
            queue.enqueueIfAbsent(entry.prepareDeliveryJob())
        }
        return entry
    }

    override suspend fun getPending(id: UUID): NotificationOutboxEntry? = repository.getPending(id)

    override suspend fun markSent(id: UUID) = repository.markSent(id)

    override suspend fun markFailure(id: UUID, error: String) = repository.markFailure(id, error)

    override suspend fun recoverPending(limit: Int): Int {
        val entries = repository.pendingImmediate(limit.coerceIn(1, 1_000))
        entries.forEach { queue.enqueueIfAbsent(it.prepareDeliveryJob()) }
        return entries.size
    }

    override suspend fun deliverDueDigests(limit: Int): Int {
        var delivered = 0
        val groups = repository.dueDigests(limit.coerceIn(1, 2_000)).groupBy { it.channel to it.target }
        for (entries in groups.values) {
            try {
                deliveryService.deliverDigest(entries)
                delivered += repository.markSent(entries.map { it.id })
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e::class.simpleName.orEmpty()
                entries.forEach { entry ->
                    runCatching { repository.markFailure(entry.id, message) }
                        .onFailure(e::addSuppressed)
                }
                outboxLog.error(
                    "Digest delivery failed for {} {} entry or entries: {}",
                    entries.first().target,
                    entries.size,
                    message,
                    e,
                )
            }
        }
        return delivered
    }

    companion object {
        private val outboxLog = LoggerFactory.getLogger(NotificationOutboxServiceImpl::class.java)
    }
}

@ServiceImplementation
class NotificationSubscriptionServiceImpl(
    private val repository: NotificationSubscriptionRepository,
) : NotificationSubscriptionService {
    override suspend fun listForProfile(profileId: UUID) = repository.listForProfile(profileId)
    override suspend fun listAll() = repository.listAll()
    override suspend fun upsert(savedFilterId: UUID, profileId: UUID, cron: String, timeZone: String) =
        repository.upsert(savedFilterId, profileId, cron, timeZone)

    override suspend fun delete(savedFilterId: UUID, profileId: UUID) = repository.delete(savedFilterId, profileId)
    override suspend fun touchRunAt(savedFilterId: UUID, profileId: UUID) =
        repository.touchRunAt(savedFilterId, profileId)
}

@ServiceImplementation
class NotificationChannelDeliveryServiceImpl(
    private val pipelineService: PipelineService,
) : NotificationChannelDeliveryService {

    override suspend fun deliver(entry: NotificationOutboxEntry) {
        val payload = entry.payload as? JsonObject
            ?: error("Notification outbox entry ${entry.id} payload is not an object")
        when (entry.channel ?: error("Notification outbox entry ${entry.id} has no notification channel")) {
            NotificationChannel.EMAIL -> if (entry.event == AUTOMATION_EMAIL_EVENT) {
                requirePipeline(AUTOMATION_EMAIL_EVENT_TYPE)
                AutomationEmailRequested(
                    recipientIds = setOf(UUID.parse(entry.target)),
                    subject = payload.stringValue("subject")
                        ?: error("Automation email outbox entry ${entry.id} has no subject"),
                    body = requirePayloadBody(entry, payload),
                    deliveryId = entry.id,
                ).dispatch()
            } else {
                requirePipeline(WORKOPS_EMAIL_EVENT_TYPE)
                dispatchWorkOpsEmail(
                    entry.id,
                    UUID.parse(entry.target),
                    requirePayloadBody(entry, payload),
                    payload,
                )
            }
            NotificationChannel.WEBHOOK -> {
                requirePipeline(WORKOPS_CHANNEL_EVENT_TYPE)
                dispatchWorkOpsChannel(entry.id, NotificationChannel.WEBHOOK, entry.target, payload)
            }
            NotificationChannel.SLACK -> {
                requirePipeline(WORKOPS_CHANNEL_EVENT_TYPE)
                dispatchWorkOpsChannel(entry.id, NotificationChannel.SLACK, entry.target, payload)
            }
            NotificationChannel.IN_APP -> error("IN_APP outbox entry ${entry.id} is invalid")
        }
    }

    override suspend fun deliverDigest(entries: List<NotificationOutboxEntry>) {
        require(entries.isNotEmpty()) { "A notification digest needs at least one entry" }
        val first = entries.first()
        val channel = first.channel
            ?: error("Notification outbox digest entry ${first.id} has no notification channel")
        require(entries.all { it.channel == channel && it.target == first.target }) {
            "A notification digest may contain only one target and channel"
        }
        val payloads = entries.map { entry ->
            entry.payload as? JsonObject
                ?: error("Notification outbox entry ${entry.id} payload is not an object")
        }
        when (channel) {
            NotificationChannel.EMAIL -> {
                require(entries.none { it.event == AUTOMATION_EMAIL_EVENT }) {
                    "Automation emails cannot be combined into a notification digest"
                }
                requirePipeline(WORKOPS_EMAIL_EVENT_TYPE)
                val profileId = UUID.parse(first.target)
                val body = payloads.joinToString("\n\n") { payload ->
                    val key = payload.stringValue("entityKey") ?: "WorkOps"
                    val title = payload.stringValue("title") ?: "Activity"
                    val detail = payload.stringValue("body") ?: ""
                    "$key: $title\n$detail".trimEnd()
                }
                dispatchWorkOpsEmail(
                    deliveryId = first.id,
                    profileId = profileId,
                    body = body,
                    payload = buildJsonObject {
                        put("event", "DAILY_DIGEST")
                        put("projectName", "Daily digest")
                        put("entityType", "DIGEST")
                        put("entityId", profileId.toString())
                        put("entityKey", "WorkOps")
                        put("title", "${entries.size} updates")
                        put("linkPath", "/workops")
                    },
                )
            }
            NotificationChannel.WEBHOOK, NotificationChannel.SLACK -> {
                requirePipeline(WORKOPS_CHANNEL_EVENT_TYPE)
                val payload = buildJsonObject {
                    put("digest", true)
                    put("notifications", JsonArray(payloads))
                }
                dispatchWorkOpsChannel(first.id, channel, first.target, payload)
            }
            NotificationChannel.IN_APP -> error("IN_APP notification digests are invalid")
        }
    }

    private fun requirePayloadBody(entry: NotificationOutboxEntry, payload: JsonObject): String =
        payload.stringValue("body") ?: error("Notification outbox entry ${entry.id} has no body")

    private suspend fun requirePipeline(eventType: String) {
        check(eventType in pipelineService.triggeredEventTypes()) {
            "No triggered pipeline is configured for notification event $eventType"
        }
    }

    companion object {
        const val AUTOMATION_EMAIL_EVENT = "AUTOMATION_EMAIL"
        const val AUTOMATION_EMAIL_EVENT_TYPE = "bosca.workops.model.automation.AutomationEmailRequested"
        const val WORKOPS_EMAIL_EVENT_TYPE = "bosca.workops.model.notification.WorkOpsEmailRequested"
        const val WORKOPS_CHANNEL_EVENT_TYPE = "bosca.workops.model.notification.WorkOpsChannelRequested"
    }
}

private suspend fun dispatchWorkOpsEmail(
    deliveryId: UUID,
    profileId: UUID,
    body: String,
    payload: JsonObject,
) {
    val entityId = payload.stringValue("entityId")?.let { runCatching { UUID.parse(it) }.getOrNull() }
        ?: error("Email notification payload has no valid 'entityId' for profile $profileId")
    WorkOpsEmailRequested(
        recipientIds = setOf(profileId),
        event = payload.requireString("event", profileId),
        projectName = payload.requireString("projectName", profileId),
        entityType = payload.requireString("entityType", profileId),
        entityId = entityId,
        entityKey = payload.requireString("entityKey", profileId),
        title = payload.requireString("title", profileId),
        body = body,
        actorName = payload.stringValue("actorName"),
        linkPath = payload.requireString("linkPath", profileId),
        deliveryId = deliveryId,
    ).dispatch()
}

private suspend fun dispatchWorkOpsChannel(
    deliveryId: UUID,
    channel: NotificationChannel,
    target: String,
    payload: JsonObject,
) {
    WorkOpsChannelRequested(deliveryId, channel, target, payload).dispatch()
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

private fun JsonObject.requireString(key: String, profileId: UUID): String =
    stringValue(key) ?: error("Email notification payload is missing '$key' for profile $profileId")
