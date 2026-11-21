package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Build and bundle remain separate API writes; retries keep the created release identity. */
@Serializable
class WorkOpsReleaseBuildModel(
    val programId: String,
    var programName: String = "",
    var active: Boolean = false,
    var draft: WorkOpsReleaseDraft = WorkOpsReleaseDraft(),
    var picker: WorkOpsReleasePickerModel = WorkOpsReleasePickerModel(programId),
    var createdId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val createdHref get() = if (createdId.isBlank()) "" else "/workops/releases/${workOpsEncode(createdId)}"
    val hasPending get() = createdId.isNotBlank() && draft.components.any { !it.bundled }
    val bundledCount get() = draft.components.count { it.bundled }
    val planned get() = workOpsUtcDate(draft.date).orEmpty()

    suspend fun open(ctx: RenderContext) = feedback.perform {
        val program = ctx.gql.execute(WorkOpsReleasePrograms, Unit).workOps.programs.all.firstOrNull { it.id == programId }
        requireNotNull(program) { "Choose an available program." }
        programName = program.name; active = true; draft = WorkOpsReleaseDraft(); createdId = ""
        picker = WorkOpsReleasePickerModel(programId)
        picker.reloadChoices(ctx)
    }
    fun close() { active = false; picker.creatingVersion = false }
    suspend fun detailsNext(name: String, description: String, date: String) = feedback.perform {
        require(active && createdId.isBlank()) { "Open a new release first." }
        draft.name = name; draft.description = description; draft.date = date
        require(name.isNotBlank()) { "Enter a release name." }
        workOpsUtcDate(date)
        draft.step = 2
    }
    fun projectsNext() { require(active && createdId.isBlank()); draft.step = 3; feedback.message = ""; feedback.failed = false }
    fun back() { require(active && createdId.isBlank()); draft.step = (draft.step - 1).coerceAtLeast(1); feedback.message = ""; feedback.failed = false }
    suspend fun stage(project: String, version: String) = picker.feedback.perform {
        require(active && createdId.isBlank()) { "Open a new release first." }
        picker.capture(project, version)
        draft.components = draft.components + picker.selection(project, version)
        picker.excludedVersionIds = draft.components.map { it.versionId }; picker.pickVersionId = ""
    }
    fun removeStage(id: String) {
        require(createdId.isBlank()); draft.components = draft.components.filterNot { it.versionId == id }
        picker.excludedVersionIds = draft.components.map { it.versionId }
    }
    suspend fun chooseProject(project: String, ctx: RenderContext) = picker.chooseProject(project, ctx)
    suspend fun reloadPicker(project: String, version: String, ctx: RenderContext) = picker.reloadPicker(project, version, ctx)
    suspend fun openVersion(project: String) = picker.openVersion(project)
    fun cancelVersion() = picker.cancelVersion()
    suspend fun createVersion(name: String, description: String, start: String, release: String, ctx: RenderContext) = picker.createVersion(name, description, start, release, ctx)
    fun resume() { require(hasPending) { "No versions are waiting to be bundled." }; active = true; draft.step = 3 }
    suspend fun build(ctx: RenderContext) = feedback.perform {
        require(active && draft.step == 3) { "Review the release first." }
        require(draft.name.isNotBlank()) { "Enter a release name." }
        val date = workOpsUtcDate(draft.date)
        if (createdId.isBlank()) {
            createdId = ctx.gql.execute(WorkOpsCreateRelease, WorkOpsCreateRelease.Variables(CreateWorkOpsReleaseInput(
                programId = programId, name = draft.name.trim(), description = draft.description.trim().takeIf(String::isNotBlank), releaseDate = date,
            ))).workOps.crossProject.createRelease.id
        }
        for (component in draft.components.filterNot { it.bundled }) {
            val result = WorkOpsFeedback()
            result.perform {
                require(ctx.gql.execute(WorkOpsBundleReleaseVersion, WorkOpsBundleReleaseVersion.Variables(createdId, component.projectId, component.versionId))
                    .workOps.crossProject.bundleVersion) { "The version could not be bundled." }
            }
            draft.components = draft.components.map {
                if (it.versionId == component.versionId) it.copy(bundled = !result.failed, error = result.message) else it
            }
        }
        if (hasPending) { feedback.failed = true; feedback.message = "Release created. $bundledCount of ${draft.components.size} versions bundled." }
        else { active = false; feedback.message = if (draft.components.isEmpty()) "Release created." else "Release built." }
    }
}
