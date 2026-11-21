package bosca.workops.ui

import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.ChartConfiguration
import bosca.workops.ui.graphql.WorkOpsReleaseLiveSessions
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** WorkOps owns source/version selection; the shared chart owns geographic rendering. */
@Serializable
class WorkOpsReleaseLiveModel(
    val id: String,
    var readContext: String = "",
    val sources: WorkOpsReleaseTelemetrySourcesModel = WorkOpsReleaseTelemetrySourcesModel(id),
    var thisReleaseOnly: Boolean = true,
    var expanded: Boolean = false,
    var streamRevision: Long = 0,
) {
    val streams get() = if (!sources.loaded) emptyList() else sources.applications.mapNotNull { source ->
        if (thisReleaseOnly && source.versionName.isBlank()) null else WorkOpsReleaseLiveSource(
            source.identifier, if (thisReleaseOnly) source.versionName else null, source.projectId, source.projectLabel,
        )
    }.distinctBy { listOf(it.appId, it.appVersion, it.projectId) }
    val unavailableVersions get() = if (thisReleaseOnly && sources.loaded) sources.applications.count { it.versionName.isBlank() } else 0
    val streamsJson get() = Json.encodeToString(ListSerializer(WorkOpsReleaseLiveSource.serializer()), streams)
    val subscription get() = WorkOpsReleaseLiveSessions.document
    val chart get() = ChartConfiguration(buildJsonObject {
        put("animation", false)
        putJsonObject("tooltip") { put("trigger", "item") }
        putJsonObject("legend") { put("type", "scroll"); put("bottom", 0) }
        putJsonObject("geo") { put("map", "world"); put("roam", true); put("preserveAspect", "contain"); put("left", 8); put("right", 8); put("top", 8); put("bottom", 40) }
        putJsonArray("series") {
            val colors = listOf("var(--accent)", "var(--ok)", "var(--warn)", "var(--info)", "var(--err)", "var(--purple)")
            for ((index, project) in streams.distinctBy { it.projectId }.sortedBy { it.projectId }.withIndex()) add(buildJsonObject {
                put("id", project.projectId); put("name", project.projectLabel); put("type", "scatter"); put("coordinateSystem", "geo"); put("symbolSize", 6)
                putJsonObject("itemStyle") { put("color", colors[index % colors.size]) }; put("data", JsonArray(emptyList()))
            })
        }
    })

    /** Refreshes authorized source metadata; an explicit user refresh also reconnects its streams. */
    suspend fun refreshContext(revision: String, ctx: RenderContext) {
        sources.refresh(ctx)
        if (revision.isBlank()) streamRevision++ else readContext = revision
    }
    /** Applies the viewer's explicit version scope after refreshing its source authority. */
    suspend fun toggleScope(ctx: RenderContext) { thisReleaseOnly = !thisReleaseOnly; refreshContext("", ctx) }
    fun openMap() { expanded = true }
    fun closeMap() { expanded = false }
}

suspend fun workOpsReleaseLive(id: String, revision: String) = WorkOpsReleaseLiveModel(id, revision).also { it.refreshContext(revision, currentRenderContext()) }
