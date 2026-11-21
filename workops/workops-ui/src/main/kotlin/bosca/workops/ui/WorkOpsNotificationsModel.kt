package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.workops.ui.graphql.WorkOpsNotificationSchemes
import kotlinx.serialization.Serializable

/** Scheme reads and caller preferences keep independent state and reuse the Inbox preference contract. */
@Serializable
class WorkOpsNotificationsModel(
    val tab: String = "schemes", var schemeId: String = "", val offset: Long = 0,
    var schemes: List<WorkOpsNotificationSchemeRecord> = emptyList(), var loaded: Boolean = false,
    val preferences: WorkOpsInboxModel = WorkOpsInboxModel(tab = "preferences"), val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val selected get() = schemes.firstOrNull { it.id == schemeId }
    val tabs get() = listOf(NavigationItem("Schemes", href("schemes", 0), tab == "schemes"), NavigationItem("Preferences", href("preferences", 0), tab == "preferences"))
    val pageEvents get() = selected?.events.orEmpty().drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val previousHref get() = if (offset > 0) href("schemes", (offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (selected?.events.orEmpty().size > offset + 25) href("schemes", offset + 25) else ""
    private fun href(section: String, page: Long) = "/workops/settings/notifications?tab=$section&schemeId=${workOpsEncode(schemeId)}&offset=$page"
    suspend fun retry(ctx: RenderContext) = feedback.perform {
        loaded = false
        schemes = ctx.gql.execute(WorkOpsNotificationSchemes, Unit).workOps.notifications.schemes.map { WorkOpsNotificationSchemeRecord(it.id, it.name, it.description.orEmpty(), it.eventRecipients) }
        if (schemeId.isBlank()) schemeId = schemes.firstOrNull()?.id.orEmpty()
        loaded = true
    }
    suspend fun retryPreferences(ctx: RenderContext) = preferences.retry(ctx)
    suspend fun savePreferences(attributes: String, ctx: RenderContext) = preferences.savePreferences(attributes, ctx)
}

suspend fun workOpsNotifications(): WorkOpsNotificationsModel {
    val ctx = currentRenderContext()
    return WorkOpsNotificationsModel(if (ctx.query["tab"] == "preferences") "preferences" else "schemes", ctx.query["schemeId"].orEmpty(), ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also {
        if (it.tab == "preferences") it.retryPreferences(ctx) else it.retry(ctx)
    }
}
