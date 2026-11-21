package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/** HTTP summaries use the existing one-hour counter windows; trends join actual timestamps. */
@Serializable
class WorkOpsReleaseServiceHealth(
    val identifier: String,
    var projects: List<String> = emptyList(),
    var success: WorkOpsHealthCounter? = null,
    var client: WorkOpsHealthCounter? = null,
    var server: WorkOpsHealthCounter? = null,
    var loaded: Boolean = false,
    var checked: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val total: Long? get() {
        val good = success?.value ?: return null
        val clientErrors = client?.value ?: return null
        val serverErrors = server?.value ?: return null
        return good + clientErrors + serverErrors
    }
    val errorRate: Double? get() {
        val responses = total ?: return null
        val clientErrors = client?.value ?: return null
        val serverErrors = server?.value ?: return null
        return if (responses == 0L) 0.0 else (clientErrors + serverErrors).toDouble() / responses * 100
    }
    val errorRateLabel get() = errorRate?.let { String.format(Locale.ROOT, "%.2f%%", it) } ?: "Unavailable"
    val tone get() = when {
        !loaded || total == null || total == 0L -> "neutral"
        (errorRate ?: 0.0) >= 5 -> "error"
        (errorRate ?: 0.0) >= 1 -> "warning"
        else -> "success"
    }
    val chart get() = workOpsHealthChart("HTTP error rate", workOpsHttpRateSamples(success, client, server), percent = true,
        color = when (tone) { "error" -> "var(--err)"; "warning" -> "var(--warn)"; "success" -> "var(--ok)"; else -> "var(--fg-2)" })
    suspend fun refresh(ctx: RenderContext) = feedback.perform {
        loaded = false
        val values = ctx.gql.execute(WorkOpsReleaseServiceHttp, WorkOpsReleaseServiceHttp.Variables("http.$identifier.2xx", "http.$identifier.4xx", "http.$identifier.5xx")).analytics.counters
        val good = values.success.healthCounter(); val clientErrors = values.client.healthCounter(); val serverErrors = values.server.healthCounter()
        success = good; client = clientErrors; server = serverErrors
        checked = OffsetDateTime.now(ZoneOffset.UTC).toString(); loaded = true
    }
}
