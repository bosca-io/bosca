package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Refreshes each bundled version independently without turning failed reads into empty publications. */
@Serializable
class WorkOpsReleaseArtifactsModel(
    val id: String,
    var components: List<WorkOpsReleaseComponentRecord> = emptyList(),
    var componentsLoaded: Boolean = false,
    var declarations: List<WorkOpsReleaseArtifactRecord> = emptyList(),
    var declarationsLoaded: Boolean = false,
    var publications: List<WorkOpsReleaseArtifactRecord> = emptyList(),
    var publicationErrors: Map<String, String> = emptyMap(),
    var needsPolling: Boolean = false,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val labelFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val declarationFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val runFeedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val rows get() = publications + declarations.mapNotNull { declared ->
        val registered = publications.filter { it.identity == declared.identity }
        when {
            registered.any { it.environments.isEmpty() } -> null
            declared.environments.isEmpty() -> declared.copy(stale = !declarationsLoaded)
            else -> declared.environments.filter { environment -> registered.none { environment in it.environments } }
                .takeIf { it.isNotEmpty() }?.let { declared.copy(environments = it, stale = !declarationsLoaded) }
        }
    }
    val verifiedEmpty get() = componentsLoaded && declarationsLoaded && publicationErrors.isEmpty() && rows.isEmpty()
    fun projectLabel(row: WorkOpsReleaseArtifactRecord) = components.firstOrNull { it.projectId == row.projectId }?.projectLabel ?: "Project unavailable"
    fun versionLabel(row: WorkOpsReleaseArtifactRecord): String = if (row.versionId.isNotBlank())
        components.firstOrNull { it.versionId == row.versionId }?.versionLabel ?: "Version unavailable"
    else components.filter { it.projectId == row.projectId }.map { it.versionLabel }.distinct().joinToString(", ").ifBlank { "Version unavailable" }

    suspend fun refresh(ctx: RenderContext) {
        componentsLoaded = false; declarationsLoaded = false
        publications = publications.map { it.copy(stale = true) }
        feedback.perform {
            components = workOpsReleaseComponents(id, ctx); componentsLoaded = true
            labelFeedback.perform { components = workOpsReleaseComponentLabels(components, ctx) }
            val versionIds = components.map { it.versionId }.toSet()
            publications = publications.filter { it.versionId in versionIds }
            declarations = declarations.filter { declared -> components.any { it.projectId == declared.projectId } }
            val errors = mutableMapOf<String, String>()
            for (versionId in versionIds) {
                val read = WorkOpsFeedback()
                read.perform {
                    val fresh = ctx.gql.execute(WorkOpsReleaseVersionArtifacts, WorkOpsReleaseVersionArtifacts.Variables(versionId))
                        .workOps.multiRepo.artifacts.map { artifact -> WorkOpsReleaseArtifactRecord(
                            artifact.projectId, artifact.artifactType.name, artifact.namespace.orEmpty(), artifact.coordinates,
                            artifact.environments, artifact.versionId, artifact.id, artifact.status.name,
                        ) }
                    publications = publications.filter { it.versionId != versionId } + fresh
                }
                if (read.failed) errors[versionId] = read.message
            }
            publicationErrors = errors
            declarationFeedback.perform {
                declarations = ctx.gql.execute(WorkOpsReleaseDeclaredArtifacts, WorkOpsReleaseDeclaredArtifacts.Variables(id))
                    .workOps.crossProject.releaseDeclaredArtifacts.map { WorkOpsReleaseArtifactRecord(it.projectId, it.type.name, it.namespace, it.coordinate, it.environments) }
                declarationsLoaded = true
            }
        }
        runFeedback.perform {
            needsPolling = ctx.gql.execute(WorkOpsReleaseRunStatuses, WorkOpsReleaseRunStatuses.Variables(id))
                .workOps.crossProject.releaseRuns.any { it.status in setOf("QUEUED", "RUNNING") }
        }
        if (runFeedback.failed) needsPolling = true
    }
}
