package bosca.workops.model.notification

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Events supported by WorkOps notification schemes and delivery. */
@Serializable
enum class NotificationEvent {
    TASK_CREATED,
    TASK_UPDATED,
    TASK_ASSIGNED,
    TASK_RESOLVED,
    TASK_CLOSED,
    TASK_REOPENED,
    TASK_COMMENTED,
    TASK_COMMENT_EDITED,
    TASK_COMMENT_DELETED,
    TASK_TRANSITIONED,
    TASK_LINKED,
    TASK_DELETED,
    WORKLOG_LOGGED,
    WORKLOG_UPDATED,
    TASK_DUE,
    SLA_BREACHED,
    SLA_AT_RISK,
    MENTIONED,
    WATCH_ADDED,
    WATCH_REMOVED,
    SPRINT_STARTED,
    SPRINT_CLOSED,
    SPEC_CREATED,
    SPEC_UPDATED,
    SPEC_DELETED,
    SPEC_TRANSITIONED,
    SPEC_COMMENTED,
    SPEC_TASKS_GENERATED,
    REQUIREMENT_COMMENTED,
    PIPELINE_APPROVAL_REQUESTED,
}

/**
 * Channels supported by the dispatcher. `IN_APP` is always
 * writable — every authenticated profile has an in-app inbox.
 * External channels are staged in the WorkOps notification outbox;
 * its delivery worker hands them to their configured pipelines.
 */
@Serializable
@DbMapper(NotificationChannelMapper::class)
enum class NotificationChannel {
    IN_APP,
    EMAIL,
    WEBHOOK,
    SLACK,
}

object NotificationChannelMapper : EnumMapper<NotificationChannel>({ NotificationChannel.valueOf(it.uppercase()) })

/** Internal non-notification work currently sharing the durable outbox table. */
@Serializable
@DbMapper(NotificationOutboxActionMapper::class)
enum class NotificationOutboxAction {
    AUTOMATION_COMMENT,
}

object NotificationOutboxActionMapper :
    EnumMapper<NotificationOutboxAction>({ NotificationOutboxAction.valueOf(it.uppercase()) })

/**
 * Recipient resolver kinds. The dispatcher walks the scheme's
 * `eventRecipients[event]` list and resolves each variant against
 * the task / project / actor context. Mirrors R12.
 */
@Serializable
sealed class NotificationRecipient {
    @Serializable @SerialName("Reporter")
    data object Reporter : NotificationRecipient()

    @Serializable @SerialName("Assignee")
    data object Assignee : NotificationRecipient()

    @Serializable @SerialName("Watchers")
    data object Watchers : NotificationRecipient()

    /** Owner of the current spec, or the project owner when no narrower owner exists. */
    @Serializable @SerialName("Owner")
    data object Owner : NotificationRecipient()

    /** Same as [Assignee] but pinned to the assignee at the moment of the event. */
    @Serializable @SerialName("CurrentAssignee")
    data object CurrentAssignee : NotificationRecipient()

    /** A project-scoped security group present in the project's entity-permission grants. */
    @Serializable @SerialName("ProjectRole")
    data class ProjectRole(@Contextual val roleId: UUID) : NotificationRecipient()

    @Serializable @SerialName("Group")
    data class Group(@Contextual val groupId: UUID) : NotificationRecipient()

    @Serializable @SerialName("Profile")
    data class Profile(@Contextual val profileId: UUID) : NotificationRecipient()

    /** All profiles resolved from canonical `@profile-slug` handles in the comment. */
    @Serializable @SerialName("MentionedUsers")
    data object MentionedUsers : NotificationRecipient()

    /** Whoever the task's [fieldKey] resolves to (profile-id field). */
    @Serializable @SerialName("CustomFieldValue")
    data class CustomFieldValue(val fieldKey: String) : NotificationRecipient()
}

/**
 * Persisted notification scheme. The `eventRecipients` jsonb stores
 * the map `NotificationEvent → List<NotificationRecipient>`. A
 * decoded view lives in [TypedNotificationScheme].
 */
@Serializable
data class NotificationScheme(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    @ColumnName("event_recipients")
    @Contextual
    val eventRecipients: JsonElement = JsonObject(emptyMap()),
    val version: Long = 0,
)

/** Decoded view threaded through the dispatcher's hot path. */
data class TypedNotificationScheme(
    val id: UUID,
    val name: String,
    val description: String?,
    val recipientsByEvent: Map<String, List<NotificationRecipient>>,
    val version: Long,
)

/**
 * Per-profile notification preferences. The
 * `eventChannels` jsonb holds `Map<NotificationEvent, Set<NotificationChannel>>`.
 * `dndStartLocal` / `dndEndLocal` bound a daily do-not-disturb window
 * (24-hour, in the profile's local time zone — caller resolves TZ
 * elsewhere). `mutedTaskIds` / `mutedProjectIds` filter at fan-out
 * time.
 */
@Serializable
data class NotificationPreference(
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    @ColumnName("event_channels")
    @Contextual
    val eventChannels: JsonElement = JsonObject(emptyMap()),
    @ColumnName("watch_authored")
    val watchAuthored: Boolean = true,
    @ColumnName("watch_commented")
    val watchCommented: Boolean = true,
    @ColumnName("daily_digest")
    val dailyDigest: Boolean = false,
    @ColumnName("dnd_start_local")
    val dndStartLocal: String? = null,
    @ColumnName("dnd_end_local")
    val dndEndLocal: String? = null,
    @ColumnName("muted_task_ids")
    val mutedTaskIds: List<@Contextual UUID> = emptyList(),
    @ColumnName("muted_project_ids")
    val mutedProjectIds: List<@Contextual UUID> = emptyList(),
    val version: Long = 0,
)

/**
 * In-app inbox row. Every recipient resolved by the dispatcher
 * against [NotificationChannel.IN_APP] gets one of these.
 */
@Serializable
data class Notification(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("source_id")
    @Contextual
    val sourceId: UUID? = null,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    /**
     * Stored as the enum's `name`. The dispatcher writes via the
     * enum and the reader can decode with [NotificationEvent.valueOf].
     */
    val event: String,
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID? = null,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID? = null,
    @ColumnName("actor_profile_id")
    @Contextual
    val actorProfileId: UUID? = null,
    val body: String,
    val link: String? = null,
    @ColumnName("read_at")
    @Contextual
    val readAt: OffsetDateTime? = null,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * Per-saved-filter, per-profile digest subscription. The cron is
 * stored as a quartz-friendly string (e.g. `0 0 9 * * ?` = 09:00
 * daily). [SavedFilterDigestJob] iterates these rows and dispatches
 * email digests.
 */
@Serializable
data class NotificationSubscription(
    @ColumnName("saved_filter_id")
    @Contextual
    val savedFilterId: UUID,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    val cron: String,
    @ColumnName("time_zone")
    val timeZone: String,
    @ColumnName("last_run_at")
    @Contextual
    val lastRunAt: OffsetDateTime? = null,
)

/**
 * Task-level watcher row. Comments-mention auto-watch and the
 * `watch tasks I create` preference both insert here.
 */
@Serializable
data class TaskWatcher(
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    @ColumnName("added_at")
    @Contextual
    val addedAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * Outbox row for async channel delivery. Email / Webhook / Slack
 * all enqueue through this single table — a worker pulls rows and
 * dispatches them with retry / backoff. The payload is opaque to
 * the worker (each channel decodes its own shape).
 */
@Serializable
data class NotificationOutboxEntry(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("source_id")
    @Contextual
    val sourceId: UUID,
    val event: String,
    val channel: NotificationChannel?,
    val action: NotificationOutboxAction? = null,
    val target: String,
    @Contextual
    val payload: JsonElement,
    val attempts: Int = 0,
    @ColumnName("last_error")
    val lastError: String? = null,
    @ColumnName("sent_at")
    @Contextual
    val sentAt: OffsetDateTime? = null,
    @ColumnName("available_at")
    @Contextual
    val availableAt: OffsetDateTime? = null,
    val digest: Boolean = false,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
