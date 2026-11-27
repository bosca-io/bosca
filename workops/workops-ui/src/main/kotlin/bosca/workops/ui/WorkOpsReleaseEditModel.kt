package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Details use the inspected revision; bundle changes remain independent ID-based API writes. */
@Serializable
class WorkOpsReleaseEditModel(
    val id: String,
    var release: WorkOpsReleaseRecord? = null,
    var canManage: Boolean = false,
    var editing: WorkOpsReleaseRecord? = null,
    var nameDraft: String = "",
    var descriptionDraft: String = "",
    var dateDraft: String = "",
    var picker: WorkOpsReleasePickerModel = WorkOpsReleasePickerModel(release?.programId.orEmpty()),
    var components: List<WorkOpsReleaseComponentRecord> = emptyList(),
    var componentsLoaded: Boolean = false,
    var removing: WorkOpsReleaseComponentRecord? = null,
    var deleting: WorkOpsReleaseRecord? = null,
    var nextHref: String = "",
    var revision: Long = 0,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val readFeedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val href get() = "/workops/releases/${workOpsEncode(id)}"
    val listHref get() = "/workops/releases" + release?.programId?.let { "?programId=${workOpsEncode(it)}" }.orEmpty()
    private fun capture(name: String, description: String, date: String) {
        nameDraft = name; descriptionDraft = description; dateDraft = date
    }
    private fun seed(row: WorkOpsReleaseRecord) { capture(row.name, row.description, row.planned.take(10)) }
    suspend fun open(ctx: RenderContext) = feedback.perform {
        val row = requireNotNull(ctx.gql.execute(WorkOpsReleaseDetail, WorkOpsReleaseDetail.Variables(id)).workOps.crossProject.release?.releaseRecord()) { "This release is unavailable." }
        release = row; editing = row; seed(row); removing = null
        components = emptyList(); componentsLoaded = false
        picker = WorkOpsReleasePickerModel(row.programId)
        reloadComponents(ctx)
        picker.reloadChoices(ctx)
    }
    fun close() { editing = null; removing = null; picker.cancelVersion() }
    private suspend fun reloadComponents(ctx: RenderContext) = readFeedback.perform {
        componentsLoaded = false
        components = workOpsReleaseComponents(id, ctx); componentsLoaded = true
        picker.excludedVersionIds = components.map { it.versionId }
        components = workOpsReleaseComponentLabels(components, ctx)
    }
    suspend fun save(name: String, description: String, date: String, project: String, version: String, ctx: RenderContext) = feedback.perform {
        capture(name, description, date)
        picker.capture(project, version)
        val target = requireNotNull(editing) { "Open the release editor first." }
        require(name.isNotBlank()) { "Enter a release name." }
        val updated = ctx.gql.execute(WorkOpsUpdateRelease, WorkOpsUpdateRelease.Variables(target.id,
            UpdateWorkOpsReleaseInput(name.trim(), description.trim().takeIf(String::isNotBlank), workOpsUtcDate(date)), target.version)).workOps.crossProject.updateRelease.releaseRecord()
        release = updated; editing = updated; seed(updated)
        revision++
        feedback.message = "Release details saved."
    }
    suspend fun reloadChoices(name: String, description: String, date: String, project: String, version: String, ctx: RenderContext) {
        capture(name, description, date)
        picker.reloadPicker(project, version, ctx)
        reloadComponents(ctx)
    }
    suspend fun chooseProject(name: String, description: String, date: String, project: String, ctx: RenderContext) {
        capture(name, description, date); picker.chooseProject(project, ctx)
    }
    suspend fun openVersion(name: String, description: String, date: String, project: String) {
        capture(name, description, date); picker.openVersion(project)
    }
    fun cancelVersion() = picker.cancelVersion()
    suspend fun createVersion(name: String, description: String, start: String, date: String, ctx: RenderContext) = picker.createVersion(name, description, start, date, ctx)
    suspend fun add(name: String, description: String, date: String, project: String, version: String, ctx: RenderContext) = feedback.perform {
        capture(name, description, date)
        requireNotNull(editing) { "Open the release editor first." }
        require(componentsLoaded) { "Reload the bundled versions before adding another." }
        picker.capture(project, version)
        val choice = picker.selection(project, version)
        require(ctx.gql.execute(WorkOpsBundleReleaseVersion, WorkOpsBundleReleaseVersion.Variables(id, project, version)).workOps.crossProject.bundleVersion) { "The version could not be bundled." }
        components = components + WorkOpsReleaseComponentRecord(choice.projectId, choice.versionId, status = "PENDING",
            projectKey = choice.projectKey, projectName = choice.projectName, versionName = choice.versionName)
        picker.excludedVersionIds = components.map { it.versionId }; picker.pickVersionId = ""
        revision++
        reloadComponents(ctx)
        feedback.message = "Version bundled."
    }
    fun beginRemove(version: String, name: String, description: String, date: String, project: String, selectedVersion: String) {
        capture(name, description, date)
        picker.capture(project, selectedVersion)
        removing = requireNotNull(components.firstOrNull { it.versionId == version }) { "Reload the bundled versions first." }
        feedback.message = ""; feedback.failed = false
    }
    fun cancelRemove() { removing = null }
    suspend fun remove(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm removal from this release." }
        val target = requireNotNull(removing) { "Choose a bundled version first." }
        require(ctx.gql.execute(WorkOpsUnbundleReleaseVersion, WorkOpsUnbundleReleaseVersion.Variables(id, target.versionId)).workOps.crossProject.unbundleVersion) { "The version could not be removed." }
        components = components.filterNot { it.versionId == target.versionId }
        picker.excludedVersionIds = components.map { it.versionId }; removing = null
        revision++
        reloadComponents(ctx)
        feedback.message = "Version removed from the release."
    }
    fun beginDelete() {
        deleting = requireNotNull(release) { "Reload this release first." }
        feedback.message = ""; feedback.failed = false
    }
    fun cancelDelete() { deleting = null }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm deletion of this release." }
        val target = requireNotNull(deleting) { "Choose a release first." }
        require(ctx.gql.execute(WorkOpsDeleteRelease, WorkOpsDeleteRelease.Variables(target.id)).workOps.crossProject.deleteRelease) { "This release is already unavailable." }
        nextHref = "/workops/releases?programId=${workOpsEncode(target.programId)}"
        deleting = null; editing = null; release = null
        feedback.message = "Release deleted."
    }
}
