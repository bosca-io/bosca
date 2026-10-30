package bosca.artifacts.ui

import bosca.artifacts.ui.graphql.*
import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import kotlinx.serialization.Serializable

@Serializable
class ArtifactRunModel(
    val id: String,
    var status: String = "",
    var error: String = "",
    var modified: String = "",
    var nodes: List<ArtifactRunNode> = emptyList(),
    val feedback: ArtifactFeedback = ArtifactFeedback(),
) {
    val active: Boolean get() = status in setOf("RUNNING", "SUSPENDED")
    suspend fun reload(ctx: RenderContext) {
        val run = ctx.gql.execute(ArtifactsRun, ArtifactsRun.Variables(id)).pipelines.run
        if (run == null) { ctx.notFound = true; return }
        status = run.status.name
        error = run.error.orEmpty()
        modified = run.modifiedAt
        nodes = run.nodes.map { ArtifactRunNode(it.nodeId, it.status.name, it.error.orEmpty()) }
    }
    suspend fun refresh(ctx: RenderContext) = feedback.perform { reload(ctx) }
    suspend fun restart(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm restarting this run against the current pipeline." }
        val run = ctx.gql.execute(ArtifactsRestartRun, ArtifactsRestartRun.Variables(id)).pipelines.restartRun
        feedback.nextHref = run?.let { "/artifacts/runs/${artifactEncode(it.id)}" }.orEmpty()
        require(run != null) { "The source run is unavailable." }
    }
    suspend fun cancel(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm cancelling the run." }
        ctx.gql.execute(ArtifactsCancelRun, ArtifactsCancelRun.Variables(id))
        reload(ctx)
    }
}

suspend fun artifactRun(): ArtifactRunModel {
    val ctx = currentRenderContext()
    return ArtifactRunModel(ctx.params["id"].orEmpty()).also { it.reload(ctx) }
}
