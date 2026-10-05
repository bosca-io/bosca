package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.notification.Notification
import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.notification.NotificationOutboxEntry
import bosca.workops.model.notification.NotificationOutboxAction
import bosca.workops.model.notification.NotificationPreference
import bosca.workops.model.notification.NotificationScheme
import bosca.workops.model.notification.NotificationSubscription
import bosca.workops.model.notification.TaskWatcher

@Repository
interface NotificationSchemeRepository {

    @Query("select * from workops.notification_scheme where id = :id")
    suspend fun getById(id: UUID): NotificationScheme?

    @Query("select * from workops.notification_scheme order by name")
    suspend fun listAll(): List<NotificationScheme>

    @Query(
        """
        insert into workops.notification_scheme (name, description, event_recipients)
        values (:name, :description, cast(:eventRecipients as jsonb))
        returning *
        """
    )
    suspend fun add(
        name: String,
        description: String?,
        eventRecipients: String,
    ): NotificationScheme

    @Query(
        """
        update workops.notification_scheme
        set name = :name,
            description = :description,
            event_recipients = cast(:eventRecipients as jsonb),
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        name: String,
        description: String?,
        eventRecipients: String,
        expectedVersion: Long,
    ): NotificationScheme?
}

/**
 * Upsert payload — bundled into a single class because the
 * Bosca KSP query mapper can only flatten one model parameter
 * per query (multiple `List<UUID>` columns each count as a model).
 */
data class NotificationPreferenceUpsertParams(
    val profileId: UUID,
    val eventChannels: String,
    val watchAuthored: Boolean,
    val watchCommented: Boolean,
    val dailyDigest: Boolean,
    val dndStartLocal: String?,
    val dndEndLocal: String?,
    val mutedTaskIds: List<UUID>,
    val mutedProjectIds: List<UUID>,
)

@Repository
interface NotificationPreferenceRepository {

    @Query("select * from workops.notification_preference where profile_id = :profileId")
    suspend fun getByProfile(profileId: UUID): NotificationPreference?

    @Query(
        """
        insert into workops.notification_preference
            (profile_id, event_channels, watch_authored, watch_commented,
             daily_digest, dnd_start_local, dnd_end_local,
             muted_task_ids, muted_project_ids)
        values
            (:profileId, cast(:eventChannels as jsonb), :watchAuthored, :watchCommented,
             :dailyDigest, :dndStartLocal, :dndEndLocal,
             :mutedTaskIds, :mutedProjectIds)
        on conflict (profile_id) do update
            set event_channels = excluded.event_channels,
                watch_authored = excluded.watch_authored,
                watch_commented = excluded.watch_commented,
                daily_digest = excluded.daily_digest,
                dnd_start_local = excluded.dnd_start_local,
                dnd_end_local = excluded.dnd_end_local,
                muted_task_ids = excluded.muted_task_ids,
                muted_project_ids = excluded.muted_project_ids,
                version = workops.notification_preference.version + 1
        returning *
        """
    )
    suspend fun upsert(input: NotificationPreferenceUpsertParams): NotificationPreference
}

@Repository
interface NotificationRepository {

    @Query(
        """
        insert into workops.notification
            (profile_id, event, task_id, project_id, actor_profile_id, body, link)
        values
            (:profileId, :event, :taskId, :projectId, :actorProfileId, :body, :link)
        returning *
        """
    )
    suspend fun add(
        profileId: UUID,
        event: String,
        taskId: UUID?,
        projectId: UUID?,
        actorProfileId: UUID?,
        body: String,
        link: String?,
    ): Notification

    @Query(
        """
        insert into workops.notification
            (source_id, profile_id, event, task_id, project_id, actor_profile_id, body, link)
        values
            (:sourceId, :profileId, :event, :taskId, :projectId, :actorProfileId, :body, :link)
        on conflict (source_id, event, profile_id) where source_id is not null do nothing
        returning *
        """
    )
    suspend fun addOnce(
        sourceId: UUID,
        profileId: UUID,
        event: String,
        taskId: UUID?,
        projectId: UUID?,
        actorProfileId: UUID?,
        body: String,
        link: String?,
    ): Notification?

    @Query(
        """
        select * from workops.notification
        where source_id = :sourceId and event = :event and profile_id = :profileId
        """
    )
    suspend fun getBySource(sourceId: UUID, event: String, profileId: UUID): Notification?

    @Query(
        """
        select * from workops.notification
        where profile_id = :profileId
        order by created_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByProfile(profileId: UUID, offset: Long, limit: Int): List<Notification>

    @Query(
        """
        select * from workops.notification
        where profile_id = :profileId and read_at is null
        order by created_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listUnread(profileId: UUID, offset: Long, limit: Int): List<Notification>

    @Query(
        """
        select count(*) from workops.notification
        where profile_id = :profileId and read_at is null
        """
    )
    suspend fun countUnread(profileId: UUID): Long

    @Query(
        """
        update workops.notification set read_at = now()
        where id = :id and profile_id = :profileId and read_at is null
        """
    )
    suspend fun markRead(id: UUID, profileId: UUID)

    @Query(
        """
        update workops.notification set read_at = now()
        where profile_id = :profileId and read_at is null
        """
    )
    suspend fun markAllRead(profileId: UUID)
}

@Repository
interface NotificationSubscriptionRepository {

    @Query("select * from workops.notification_subscription where profile_id = :profileId")
    suspend fun listForProfile(profileId: UUID): List<NotificationSubscription>

    @Query(
        """
        select * from workops.notification_subscription
        where saved_filter_id = :savedFilterId
        """
    )
    suspend fun listForFilter(savedFilterId: UUID): List<NotificationSubscription>

    @Query("select * from workops.notification_subscription order by saved_filter_id, profile_id")
    suspend fun listAll(): List<NotificationSubscription>

    @Query(
        """
        insert into workops.notification_subscription
            (saved_filter_id, profile_id, cron, time_zone)
        values
            (:savedFilterId, :profileId, :cron, :timeZone)
        on conflict (saved_filter_id, profile_id) do update
            set cron = excluded.cron,
                time_zone = excluded.time_zone
        returning *
        """
    )
    suspend fun upsert(
        savedFilterId: UUID,
        profileId: UUID,
        cron: String,
        timeZone: String,
    ): NotificationSubscription

    @Query(
        """
        delete from workops.notification_subscription
        where saved_filter_id = :savedFilterId and profile_id = :profileId
        """
    )
    suspend fun delete(savedFilterId: UUID, profileId: UUID)

    @Query(
        """
        update workops.notification_subscription set last_run_at = now()
        where saved_filter_id = :savedFilterId and profile_id = :profileId
        """
    )
    suspend fun touchRunAt(savedFilterId: UUID, profileId: UUID)
}

@Repository
interface TaskWatcherRepository {

    @Query("select * from workops.task_watcher where task_id = :taskId order by added_at")
    suspend fun listByTask(taskId: UUID): List<TaskWatcher>

    @Query("select * from workops.task_watcher where profile_id = :profileId order by added_at desc")
    suspend fun listByProfile(profileId: UUID): List<TaskWatcher>

    @Query(
        """
        insert into workops.task_watcher (task_id, profile_id)
        values (:taskId, :profileId)
        on conflict (task_id, profile_id) do nothing
        returning *
        """
    )
    suspend fun add(taskId: UUID, profileId: UUID): TaskWatcher?

    @Query(
        "delete from workops.task_watcher where task_id = :taskId and profile_id = :profileId",
        returnUpdateCount = true,
    )
    suspend fun remove(taskId: UUID, profileId: UUID): Int
}

@Repository
interface NotificationOutboxRepository {

    @Query(
        """
        insert into workops.notification_outbox (source_id, event, action, target, payload)
        values (
            :sourceId, :event, (:action)::workops.notification_outbox_action,
            :target, cast(:payload as jsonb)
        )
        """,
        returnUpdateCount = true,
    )
    suspend fun addRaw(
        sourceId: UUID,
        event: String,
        action: NotificationOutboxAction,
        target: String,
        payload: String,
    ): Int

    @Query(
        """
        insert into workops.notification_outbox
            (source_id, event, channel, target, payload, available_at, digest)
        values
            (
                :sourceId, :event, (:channel)::workops.notification_channel,
                :target, cast(:payload as jsonb), :availableAt, :digest
            )
        returning *
        """
    )
    suspend fun add(
        sourceId: UUID,
        event: String,
        channel: NotificationChannel,
        target: String,
        payload: String,
        availableAt: OffsetDateTime?,
        digest: Boolean,
    ): NotificationOutboxEntry

    @Query(
        """
        insert into workops.notification_outbox
            (source_id, event, channel, target, payload, available_at, digest)
        values
            (
                :sourceId, :event, (:channel)::workops.notification_channel,
                :target, cast(:payload as jsonb), :availableAt, :digest
            )
        on conflict (source_id, event, channel, target) do nothing
        returning *
        """
    )
    suspend fun addOnce(
        sourceId: UUID,
        event: String,
        channel: NotificationChannel,
        target: String,
        payload: String,
        availableAt: OffsetDateTime?,
        digest: Boolean,
    ): NotificationOutboxEntry?

    @Query(
        """
        select * from workops.notification_outbox
        where source_id = :sourceId
          and event = :event
          and channel = (:channel)::workops.notification_channel
          and target = :target
        """
    )
    suspend fun getBySource(
        sourceId: UUID,
        event: String,
        channel: NotificationChannel,
        target: String,
    ): NotificationOutboxEntry?

    @Query("select * from workops.notification_outbox where id = :id and sent_at is null")
    suspend fun getPending(id: UUID): NotificationOutboxEntry?

    @Query(
        """
        select * from workops.notification_outbox
        where sent_at is null
        order by created_at
        limit :limit
        """
    )
    suspend fun pending(limit: Int): List<NotificationOutboxEntry>

    @Query(
        """
        select * from workops.notification_outbox
        where sent_at is null
          and digest = false
          and channel in ('email', 'webhook', 'slack')
          and (available_at is null or available_at <= now())
        order by created_at
        limit :limit
        """
    )
    suspend fun pendingImmediate(limit: Int): List<NotificationOutboxEntry>

    @Query(
        """
        select * from workops.notification_outbox
        where sent_at is null
          and digest = true
          and channel in ('email', 'webhook', 'slack')
          and available_at <= now()
        order by available_at, created_at
        limit :limit
        """
    )
    suspend fun dueDigests(limit: Int): List<NotificationOutboxEntry>

    @Query(
        """
        update workops.notification_outbox
        set sent_at = now(), attempts = attempts + 1
        where id = :id
        """
    )
    suspend fun markSent(id: UUID)

    @Query(
        """
        update workops.notification_outbox
        set sent_at = now(), attempts = attempts + 1
        where id = any(:ids) and sent_at is null
        """,
        returnUpdateCount = true,
    )
    suspend fun markSent(ids: List<UUID>): Int

    @Query(
        """
        update workops.notification_outbox
        set attempts = attempts + 1, last_error = :error
        where id = :id
        """
    )
    suspend fun markFailure(id: UUID, error: String)

    @Query(
        """
        select count(*) from workops.notification_outbox where sent_at is null
        """
    )
    suspend fun pendingCount(): Long
}
