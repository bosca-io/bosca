package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.notification.Notification
import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationOutboxEntry
import bosca.workops.model.notification.NotificationPreference
import bosca.workops.model.notification.NotificationScheme
import bosca.workops.model.notification.NotificationSubscription
import bosca.workops.model.notification.TaskWatcher
import bosca.workops.model.notification.TypedNotificationScheme

/** Look-up + decoding for notification schemes. */
interface NotificationSchemeService : Service {
    suspend fun list(): List<NotificationScheme>
    suspend fun getById(id: UUID): NotificationScheme?
    suspend fun typed(id: UUID): TypedNotificationScheme?
}

/** Per-profile preference reads + writes. */
interface NotificationPreferenceService : Service {
    suspend fun get(profileId: UUID): NotificationPreference?
    suspend fun upsert(profileId: UUID, input: NotificationPreferenceInput): NotificationPreference
    suspend fun decodedChannels(pref: NotificationPreference, event: String): Set<NotificationChannel>
}

/** Resolves and delivers one durable WorkOps notification event. */
interface NotificationDeliveryService : Service {
    /** Delivers [delivery], including any derived semantic task event. */
    suspend fun deliver(delivery: NotificationDelivery)
}

data class NotificationPreferenceInput(
    val eventChannels: Map<String, Set<NotificationChannel>>,
    val watchAuthored: Boolean = true,
    val watchCommented: Boolean = true,
    val dailyDigest: Boolean = false,
    val dndStartLocal: String? = null,
    val dndEndLocal: String? = null,
    val mutedTaskIds: List<UUID> = emptyList(),
    val mutedProjectIds: List<UUID> = emptyList(),
)

/** Watcher CRUD. The dispatcher reads via [TaskWatcherService.list]. */
interface TaskWatcherService : Service {
    suspend fun list(taskId: UUID): List<TaskWatcher>
    suspend fun listForProfile(profileId: UUID): List<TaskWatcher>
    suspend fun add(taskId: UUID, profileId: UUID): TaskWatcher?
    suspend fun remove(taskId: UUID, profileId: UUID)
}

/** In-app inbox surface (read + mark-read). */
interface NotificationInboxService : Service {
    /** Adds a notification to a profile's in-app inbox. */
    suspend fun add(
        profileId: UUID,
        event: String,
        taskId: UUID?,
        projectId: UUID?,
        actorProfileId: UUID?,
        body: String,
        link: String?,
    ): Notification

    /**
     * Adds an inbox row once for a durable source event. Replaying the same event for the same
     * profile returns the previously inserted row rather than creating a duplicate.
     */
    suspend fun addOnce(
        sourceId: UUID,
        profileId: UUID,
        event: String,
        taskId: UUID?,
        projectId: UUID?,
        actorProfileId: UUID?,
        body: String,
        link: String?,
    ): Notification

    suspend fun list(profileId: UUID, offset: Long, limit: Int): List<Notification>
    suspend fun listUnread(profileId: UUID, offset: Long, limit: Int): List<Notification>
    suspend fun unreadCount(profileId: UUID): Long
    suspend fun markRead(id: UUID, profileId: UUID)
    suspend fun markAllRead(profileId: UUID)
}

/** Durable delivery queue for external notification channels. */
interface NotificationOutboxService : Service {
    /** Queues a serialized channel payload for later delivery. */
    suspend fun enqueue(channel: NotificationChannel, target: String, payload: String)

    /** Queues an internal outbox action that is not a notification channel. */
    suspend fun enqueue(channel: String, target: String, payload: String)

    /**
     * Stages one idempotent channel delivery for a durable source event. [availableAt] delays
     * delivery, while [digest] allows due entries to be aggregated into one channel hand-off.
     */
    suspend fun enqueueOnce(
        sourceId: UUID,
        event: String,
        channel: NotificationChannel,
        target: String,
        payload: String,
        availableAt: OffsetDateTime? = null,
        digest: Boolean = false,
    ): NotificationOutboxEntry

    /** Returns an unsent outbox row, or null when it is absent or already complete. */
    suspend fun getPending(id: UUID): NotificationOutboxEntry?

    /** Marks a successfully handed-off outbox row as sent. */
    suspend fun markSent(id: UUID)

    /** Records a failed hand-off attempt without acknowledging the row. */
    suspend fun markFailure(id: UUID, error: String)

    /** Republishes durable immediate rows whose queue publication was interrupted. */
    suspend fun recoverPending(limit: Int = 200): Int

    /** Delivers all digest groups whose profile-local delivery time has arrived. */
    suspend fun deliverDueDigests(limit: Int = 500): Int
}

/** Performs the final pipeline hand-off for durable notification outbox entries. */
interface NotificationChannelDeliveryService : Service {
    /** Delivers one immediate outbox entry. */
    suspend fun deliver(entry: NotificationOutboxEntry)

    /** Delivers one profile/channel digest assembled from due outbox entries. */
    suspend fun deliverDigest(entries: List<NotificationOutboxEntry>)
}

/** Saved-filter digest subscription CRUD. */
interface NotificationSubscriptionService : Service {
    suspend fun listForProfile(profileId: UUID): List<NotificationSubscription>
    suspend fun listAll(): List<NotificationSubscription>
    suspend fun upsert(savedFilterId: UUID, profileId: UUID, cron: String, timeZone: String): NotificationSubscription
    suspend fun delete(savedFilterId: UUID, profileId: UUID)
    suspend fun touchRunAt(savedFilterId: UUID, profileId: UUID)
}
