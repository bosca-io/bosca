package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Sessions and error groups have independent reads, authority and freshness. */
@Serializable
class WorkOpsReleaseAppHealth(
    val identifier: String,
    var projects: List<String> = emptyList(),
    var sessions: WorkOpsHealthCounter? = null,
    var sessionsLoaded: Boolean = false,
    var sessionsChecked: String = "",
    var openGroups: Long? = null,
    var fatalGroups: Long? = null,
    var latestError: String = "",
    var errorsLoaded: Boolean = false,
    var errorsChecked: String = "",
    val sessionFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val errorFeedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val chart get() = workOpsHealthChart("Active sessions", sessions?.samples.orEmpty().map { it.instant to it.value })
    val tone get() = when {
        !sessionsLoaded || !errorsLoaded -> "neutral"
        (fatalGroups ?: 0) > 0 -> "error"
        (openGroups ?: 0) > 0 -> "warning"
        (sessions?.value ?: 0) > 0 -> "success"
        else -> "neutral"
    }
    suspend fun refresh(ctx: RenderContext) {
        sessionsLoaded = false; errorsLoaded = false
        sessionFeedback.perform {
            sessions = ctx.gql.execute(WorkOpsReleaseAppSessions, WorkOpsReleaseAppSessions.Variables("sessions.$identifier")).analytics.counters.byId.healthCounter()
            sessionsChecked = OffsetDateTime.now(ZoneOffset.UTC).toString(); sessionsLoaded = true
        }
        errorFeedback.perform {
            val values = ctx.gql.execute(WorkOpsReleaseAppErrors, WorkOpsReleaseAppErrors.Variables(identifier)).analytics.errors
            val latest = values.open.edges.firstOrNull()?.lastSeen?.let { OffsetDateTime.parse(it).toInstant().toString() }.orEmpty()
            openGroups = values.open.total; fatalGroups = values.fatal.total; latestError = latest
            errorsChecked = OffsetDateTime.now(ZoneOffset.UTC).toString(); errorsLoaded = true
        }
    }
}
