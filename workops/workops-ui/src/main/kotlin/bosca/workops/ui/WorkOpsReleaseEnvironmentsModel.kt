package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** WorkOps supplies topology, compatible deployment facts and authorized names to shared controls. */
@Serializable
class WorkOpsReleaseEnvironmentsModel(
    val id: String,
    val programId: String,
    var environments: List<WorkOpsEnvironmentRecord> = emptyList(),
    var loaded: Boolean = false,
    var states: Map<String, WorkOpsReleaseEnvironmentState> = emptyMap(),
    var selected: Map<String, String> = emptyMap(),
    var projectNames: Map<String, String> = emptyMap(),
    var versionNames: Map<String, String> = emptyMap(),
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val labelFeedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val families get() = workOpsPromotionFamilies(environments)
    fun chosen(family: WorkOpsPromotionFamily) = family.environments.firstOrNull { it.id == selected[family.id] } ?: family.environments.first()
    fun state(environmentId: String) = states[environmentId] ?: WorkOpsReleaseEnvironmentState()
    fun projectName(id: String) = projectNames[id] ?: "Project unavailable"
    fun projectHref(id: String) = if (id in projectNames) "/workops/projects/${workOpsEncode(id)}" else ""
    fun versionName(id: String?) = id?.let { versionNames[it] } ?: "Version unavailable"
    fun sourceNames(environment: WorkOpsEnvironmentRecord) = environment.sources.distinct().joinToString(", ") { id -> environments.firstOrNull { it.id == id }?.name ?: "Environment unavailable" }
    fun select(familyId: String, environmentId: String) {
        require(families.firstOrNull { it.id == familyId }?.environments?.any { it.id == environmentId } == true) { "Choose an environment in this promotion family." }
        selected = selected + (familyId to environmentId)
    }
    suspend fun refresh(ctx: RenderContext) {
        loaded = false; states = states.mapValues { (_, value) -> value.copy(loaded = false) }
        feedback.perform {
            val rows = ctx.gql.execute(WorkOpsReleaseEnvironments, WorkOpsReleaseEnvironments.Variables(programId)).workOps.multiRepo.environments
            environments = rows.map { it.environmentRecord().copy(targetType = it.targetType?.name) }
            loaded = true
            states = states.filterKeys { key -> environments.any { it.id == key } }
            val previous = selected
            selected = families.associate { family ->
                val memberIds = family.environments.map { it.id }
                family.id to (previous[family.id]?.takeIf { it in memberIds }
                    ?: previous.values.firstOrNull { it in memberIds } ?: family.environments.first().id)
            }
            for (environment in environments) {
                val read = WorkOpsFeedback()
                read.perform {
                    val facts = ctx.gql.execute(WorkOpsReleaseEnvironmentFacts, WorkOpsReleaseEnvironmentFacts.Variables(environment.id, id)).workOps.multiRepo
                    states = states + (environment.id to WorkOpsReleaseEnvironmentState(facts.environmentState, facts.environmentDrift, facts.environmentCompatibleProjects.distinct(), loaded = true))
                }
                if (read.failed) states = states + (environment.id to state(environment.id).copy(loaded = false, error = read.message))
            }
        }
        projectNames = emptyMap(); versionNames = emptyMap()
        labelFeedback.perform {
            val used = states.values.flatMap { state -> state.targets.map { it.projectId } + state.differences.map { it.projectId } }.toSet()
            if (used.isEmpty()) return@perform
            val projects = ctx.gql.execute(WorkOpsVersionProjects, Unit).workOps.projects.all.associateBy { it.id }
            projectNames = projects.filterKeys { it in used }.mapValues { (_, project) -> "${project.key} · ${project.name}" }
            for (projectId in used.filter { it in projects }) {
                val versions = ctx.gql.execute(WorkOpsDeliveryVersions, WorkOpsDeliveryVersions.Variables(projectId)).workOps.versions.byProject
                versionNames = versionNames + versions.associate { it.id to it.name }
            }
        }
    }
}
