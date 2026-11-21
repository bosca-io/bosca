package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Notes readback and captured JSON drafts have separate lifecycles; writes use the existing id-only API. */
@Serializable
class WorkOpsReleaseNotesModel(
    val id: String,
    var components: List<WorkOpsReleaseComponentRecord> = emptyList(),
    var componentsLoaded: Boolean = false,
    var notes: List<WorkOpsReleaseNoteRecord> = emptyList(),
    var notesLoaded: Boolean = false,
    var canManage: Boolean = false,
    var editing: WorkOpsReleaseNoteRecord? = null,
    var editingLabel: String = "",
    var draft: String = "",
    var draftRevision: Long = 0,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val readFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val componentFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val labelFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val accessFeedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    fun label(note: WorkOpsReleaseNoteRecord): String = components.firstOrNull { it.versionId == note.versionId }
        ?.let { "${it.projectLabel} · ${it.versionLabel}" } ?: "Project/version unavailable"

    suspend fun refresh(ctx: RenderContext) {
        componentsLoaded = false
        componentFeedback.perform {
            components = workOpsReleaseComponents(id, ctx); componentsLoaded = true
            labelFeedback.perform { components = workOpsReleaseComponentLabels(components, ctx) }
        }
        notesLoaded = false
        readFeedback.perform {
            val fresh = ctx.gql.execute(WorkOpsReleaseVersionNotes, WorkOpsReleaseVersionNotes.Variables(id)).workOps.multiRepo.versionReleaseNotes
                .map { WorkOpsReleaseNoteRecord(it.id, it.versionId, it.sourceLocale, it.generatedAt.orEmpty(), it.manuallyEdited, it.variants).parsed() }
            notes = fresh; notesLoaded = true
        }
        if (componentsLoaded) notes = notes.filter { note -> components.any { it.versionId == note.versionId } }
        canManage = false
        accessFeedback.perform {
            canManage = ctx.gql.execute(WorkOpsReleaseAccess, WorkOpsReleaseAccess.Variables(id)).workOps.crossProject.releaseActionAccess?.canManage == true
        }
    }
    suspend fun beginEdit(versionId: String) = feedback.perform {
        require(canManage) { "Manage permission on the release program is required." }
        require(notesLoaded) { "Reload notes before editing." }
        val note = requireNotNull(notes.firstOrNull { it.versionId == versionId }) { "These version notes are unavailable." }
        editing = note; editingLabel = label(note)
        draft = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), note.variants); draftRevision++
    }
    fun cancel() { editing = null; draft = "" }
    suspend fun save(raw: String, ctx: RenderContext) = feedback.perform {
        draft = raw; draftRevision++
        val target = requireNotNull(editing) { "Open the notes editor first." }
        val value = Json.parseToJsonElement(raw)
        require(value is JsonArray) { "Release note variants must be an array, with one entry per configured locale." }
        ctx.gql.execute(WorkOpsEditVersionNotes, WorkOpsEditVersionNotes.Variables(id, target.versionId, value))
        editing = null; draft = ""; feedback.message = "Release notes saved."
        refresh(ctx)
    }
    suspend fun generate(ctx: RenderContext) = feedback.perform {
        ctx.gql.execute(WorkOpsGenerateVersionNotes, WorkOpsGenerateVersionNotes.Variables(id))
        feedback.message = "Localized release notes generated from tagged changes."
        refresh(ctx)
    }
}
