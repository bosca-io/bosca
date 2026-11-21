package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalTime
import java.time.format.DateTimeParseException

private val notificationDraftJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Owns inbox actions and preference mapping; rendering and form interaction stay in the Design Library. */
@Serializable
class WorkOpsInboxModel(
    val tab: String = "unread",
    val offset: Long = 0,
    var rows: List<WorkOpsNotificationRow> = emptyList(),
    var unreadCount: Long = 0,
    var hasNext: Boolean = false,
    var preferencesLoaded: Boolean = false,
    var draft: WorkOpsNotificationDraft = WorkOpsNotificationDraft().withFlags(),
    var nextHref: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val tabs get() = listOf("unread" to "Unread ($unreadCount)", "all" to "All", "preferences" to "Preferences")
        .map { (value, label) -> NavigationItem(label, "/workops/inbox?tab=$value", value == tab) }
    val previousHref get() = if (offset > 0) "/workops/inbox?tab=$tab&offset=${(offset - 30).coerceAtLeast(0)}" else ""
    val nextPageHref get() = if (hasNext) "/workops/inbox?tab=$tab&offset=${offset + 30}" else ""
    val preferenceValue get() = notificationDraftJson.encodeToJsonElement(WorkOpsNotificationDraft.serializer(), draft).jsonObject

    suspend fun reload(ctx: RenderContext) {
        nextHref = ""
        if (tab == "preferences") {
            if (!preferencesLoaded) loadPreferences(ctx)
            else unreadCount = ctx.gql.execute(WorkOpsInboxCount, Unit).workOps.notifications.unreadCount
            return
        }
        val notifications = if (tab == "all") {
            val result = ctx.gql.execute(WorkOpsAllInbox, WorkOpsAllInbox.Variables(offset)).workOps.notifications
            unreadCount = result.unreadCount
            result.mine.map { notification ->
                WorkOpsNotificationRow(notification.id, notification.event, notification.body, workOpsNotificationDestination(notification.event, notification.link, notification.taskId, notification.projectId), notification.readAt != null, notification.createdAt)
            }
        } else {
            val result = ctx.gql.execute(WorkOpsUnreadInbox, WorkOpsUnreadInbox.Variables(offset)).workOps.notifications
            unreadCount = result.unreadCount
            result.unread.map { notification ->
                WorkOpsNotificationRow(notification.id, notification.event, notification.body, workOpsNotificationDestination(notification.event, notification.link, notification.taskId, notification.projectId), notification.readAt != null, notification.createdAt)
            }
        }
        rows = notifications.take(30)
        hasNext = notifications.size > 30
    }

    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }

    suspend fun markRead(id: String, ctx: RenderContext) = feedback.perform {
        require(rows.any { it.id == id }) { "Reload the inbox before changing this notification." }
        check(ctx.gql.execute(WorkOpsMarkNotificationRead, WorkOpsMarkNotificationRead.Variables(id)).workOps.notifications.markRead) { "This notification is unavailable." }
        reload(ctx)
        feedback.message = "Notification marked read."
    }

    suspend fun open(id: String, ctx: RenderContext) = feedback.perform {
        nextHref = ""
        val row = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload the inbox before opening this notification." }
        if (!row.read) check(ctx.gql.execute(WorkOpsMarkNotificationRead, WorkOpsMarkNotificationRead.Variables(id)).workOps.notifications.markRead) { "This notification is unavailable." }
        if (row.href.isBlank()) reload(ctx)
        else {
            require(row.href.startsWith('/') && !row.href.startsWith("//")) { "This notification destination is unavailable." }
            nextHref = row.href
        }
    }

    suspend fun markAllRead(ctx: RenderContext) = feedback.perform {
        ctx.gql.execute(WorkOpsMarkAllNotificationsRead, Unit)
        reload(ctx)
        feedback.message = "All notifications marked read."
    }

    private suspend fun loadPreferences(ctx: RenderContext) {
        val result = ctx.gql.execute(WorkOpsNotificationPreferences, Unit).workOps.notifications
        unreadCount = result.unreadCount
        val saved = result.myPreferences
        draft = if (saved == null) WorkOpsNotificationDraft().withFlags() else WorkOpsNotificationDraft(
            watchAuthored = saved.watchAuthored, watchCommented = saved.watchCommented, dailyDigest = saved.dailyDigest,
            dndStartLocal = saved.dndStartLocal.orEmpty(), dndEndLocal = saved.dndEndLocal.orEmpty(),
            eventChannels = saved.eventChannels.jsonObject, mutedTaskIds = saved.mutedTaskIds, mutedProjectIds = saved.mutedProjectIds,
        ).withFlags()
        preferencesLoaded = true
    }

    suspend fun savePreferences(attributes: String, ctx: RenderContext) = feedback.perform {
        require(preferencesLoaded) { "Load notification preferences before saving." }
        draft = notificationDraftJson.decodeFromString(WorkOpsNotificationDraft.serializer(), attributes)
        fun time(value: String, label: String): String? {
            if (value.isBlank()) return null
            try { LocalTime.parse(value.trim()) }
            catch (e: DateTimeParseException) { throw IllegalArgumentException("Enter $label as a 24-hour time, such as 22:00.", e) }
            return value.trim()
        }
        ctx.gql.execute(WorkOpsSaveNotificationPreferences, WorkOpsSaveNotificationPreferences.Variables(WorkOpsNotificationPreferenceUpdateInput(
            eventChannels = draft.updatedChannels(), watchAuthored = draft.watchAuthored, watchCommented = draft.watchCommented, dailyDigest = draft.dailyDigest,
            dndStartLocal = time(draft.dndStartLocal, "the quiet-hours start"), dndEndLocal = time(draft.dndEndLocal, "the quiet-hours end"),
            mutedTaskIds = draft.mutedTaskIds, mutedProjectIds = draft.mutedProjectIds,
        )))
        loadPreferences(ctx)
        feedback.message = "Notification preferences saved."
    }

}

suspend fun workOpsInbox(): WorkOpsInboxModel {
    val ctx = currentRenderContext()
    val tab = ctx.query["tab"].takeIf { it in setOf("unread", "all", "preferences") } ?: "unread"
    return WorkOpsInboxModel(tab, ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also { it.retry(ctx) }
}
