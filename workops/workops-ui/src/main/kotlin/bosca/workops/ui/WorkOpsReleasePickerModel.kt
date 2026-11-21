package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.design.model.SchemaFormOption
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Authorized program projects and version choices shared by release creation and editing. */
@Serializable
class WorkOpsReleasePickerModel(
    val programId: String,
    var projects: List<WorkOpsPlanningRow> = emptyList(),
    var pickProjectId: String = "",
    var pickVersionId: String = "",
    var versions: List<WorkOpsProjectCatalogRow> = emptyList(),
    var createdVersions: List<WorkOpsReleaseVersionChoice> = emptyList(),
    var excludedVersionIds: List<String> = emptyList(),
    var choicesLoaded: Boolean = false,
    var versionsLoaded: Boolean = false,
    var creatingVersion: Boolean = false,
    var versionDraft: WorkOpsReleaseVersionDraft = WorkOpsReleaseVersionDraft(),
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val versionFeedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val projectChoices get() = projects.map { SchemaFormOption(it.id, "${it.key} · ${it.name}") }.let { choices ->
        if (pickProjectId.isNotBlank() && choices.none { it.value == pickProjectId }) choices + SchemaFormOption(pickProjectId, "Project unavailable", disabled = true) else choices
    }
    val offeredVersions get(): List<WorkOpsProjectCatalogRow> {
        val values = versions.associateByTo(linkedMapOf()) { it.id }
        for (created in createdVersions.filter { it.projectId == pickProjectId }) values.putIfAbsent(created.item.id, created.item)
        return values.values.filter { value -> value.id !in excludedVersionIds }.sortedByDescending { it.sequenceNumber }
    }
    val versionChoices get() = offeredVersions.map { SchemaFormOption(it.id, it.name + if (it.released) " · released" else "") }.let { choices ->
        if (pickVersionId.isNotBlank() && choices.none { it.value == pickVersionId }) choices + SchemaFormOption(pickVersionId, "Version unavailable", disabled = true) else choices
    }
    val pickedProjectName get() = projects.firstOrNull { it.id == pickProjectId }?.let { "${it.key} · ${it.name}" } ?: "Choose a project"
    suspend fun reloadChoices(ctx: RenderContext) = feedback.perform {
        choicesLoaded = false
        projects = ctx.gql.execute(WorkOpsReleasePickerProjects, Unit).workOps.projects.all.filter { it.program.id == programId }
            .map { WorkOpsPlanningRow(id = it.id, key = it.key, name = it.name) }
        choicesLoaded = true
        if (pickProjectId.isNotBlank()) {
            if (projects.any { it.id == pickProjectId }) loadVersions(ctx)
            else { versions = emptyList(); versionsLoaded = false }
        }
    }
    fun capture(project: String, version: String) {
        if (project != pickProjectId) {
            pickProjectId = project; pickVersionId = ""; versions = emptyList(); versionsLoaded = false
            creatingVersion = false; versionDraft = WorkOpsReleaseVersionDraft()
        } else pickVersionId = version
    }
    suspend fun reloadPicker(project: String, version: String, ctx: RenderContext) {
        capture(project, version)
        reloadChoices(ctx)
    }
    private suspend fun loadVersions(ctx: RenderContext) {
        versionsLoaded = false; versions = emptyList()
        versions = ctx.gql.execute(WorkOpsDeliveryVersions, WorkOpsDeliveryVersions.Variables(pickProjectId)).workOps.versions.byProject.map { it.projectCatalogRow() }
        val presentIds = versions.mapTo(hashSetOf()) { it.id }
        createdVersions = createdVersions.filter { it.projectId != pickProjectId || it.item.id in presentIds }
        versionsLoaded = true
    }
    suspend fun chooseProject(id: String, ctx: RenderContext) = feedback.perform {
        pickProjectId = id; pickVersionId = ""; creatingVersion = false; versionDraft = WorkOpsReleaseVersionDraft()
        versions = emptyList(); versionsLoaded = false
        require(choicesLoaded && projects.any { it.id == id }) { "Choose an available project." }
        loadVersions(ctx)
    }
    fun selection(project: String, version: String): WorkOpsReleaseComponentChoice {
        require(project == pickProjectId) { "Show the chosen project's versions first." }
        val selectedProject = requireNotNull(projects.firstOrNull { it.id == project }) { "Choose an available project." }
        val selectedVersion = requireNotNull(offeredVersions.firstOrNull { it.id == version }) { "Choose an available version." }
        return WorkOpsReleaseComponentChoice(project, selectedProject.key, selectedProject.name, version, selectedVersion.name)
    }
    suspend fun openVersion(project: String) = feedback.perform {
        require(choicesLoaded && projects.any { it.id == project }) { "Choose an available project." }
        if (project != pickProjectId) { pickProjectId = project; pickVersionId = ""; versions = emptyList(); versionsLoaded = false }
        creatingVersion = true; versionDraft = WorkOpsReleaseVersionDraft(); versionFeedback.message = ""; versionFeedback.failed = false
    }
    fun cancelVersion() { creatingVersion = false; versionDraft = WorkOpsReleaseVersionDraft() }
    suspend fun createVersion(name: String, description: String, start: String, release: String, ctx: RenderContext) = versionFeedback.perform {
        versionDraft = WorkOpsReleaseVersionDraft(name, description, start, release)
        require(creatingVersion) { "Open a version form first." }
        require(projects.any { it.id == pickProjectId }) { "Choose an available project." }
        require(name.isNotBlank()) { "Enter a version name." }
        val project = pickProjectId
        val created = ctx.gql.execute(WorkOpsCreateProjectVersion, WorkOpsCreateProjectVersion.Variables(CreateWorkOpsVersionInput(
            projectId = project, name = name.trim(), description = description.trim().takeIf(String::isNotBlank),
            startDate = workOpsUtcDate(start), releaseDate = workOpsUtcDate(release),
        ))).workOps.versions.create.projectCatalogRow()
        createdVersions = createdVersions.filterNot { it.item.id == created.id } + WorkOpsReleaseVersionChoice(project, created)
        pickVersionId = created.id; creatingVersion = false; versionDraft = WorkOpsReleaseVersionDraft()
        feedback.perform { loadVersions(ctx) }
        versionFeedback.message = "Version created and selected."
    }
}
