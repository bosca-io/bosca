package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.DocumentContainerOption
import bosca.workops.ui.graphql.ProfileVisibility
import bosca.workops.ui.graphql.WorkOpsReferenceCollection
import bosca.workops.ui.graphql.WorkOpsReferenceCollectionData
import bosca.workops.ui.graphql.WorkOpsReferenceMetadata
import bosca.workops.ui.graphql.WorkOpsReferenceMetadataData
import bosca.workops.ui.graphql.WorkOpsReferenceProfile
import bosca.workops.ui.graphql.WorkOpsReferenceProfileData
import kotlinx.serialization.json.JsonObject

/** Read-only reference details are loaded under the viewer's current identity on every navigation. */
class WorkOpsReferenceModel(val kind: String, val id: String, returnTo: String = "/workops/tasks", val offset: Long = 0) {
    val returnTo = returnTo.takeIf { it.startsWith("/workops/") } ?: "/workops/tasks"
    var metadata: WorkOpsReferenceMetadataData.Content.Metadata? = null
        private set
    var collection: WorkOpsReferenceCollectionData.Content.Collections.Collection? = null
        private set
    var profile: WorkOpsReferenceProfileData.Profiles.Profile? = null
        private set
    val feedback = WorkOpsFeedback()
    val available get() = metadata != null || collection != null || profile != null
    val title get() = metadata?.let { workOpsContentLabel(it.name, it.id) }
        ?: collection?.name?.ifBlank { "Untitled collection" } ?: profile?.name?.ifBlank { profile?.slug.orEmpty().ifBlank { "Profile" } }
        ?: "Reference unavailable"
    val kindLabel get() = when {
        metadata?.document != null -> "Document"
        metadata != null -> "Content"
        collection != null -> "Collection"
        profile != null -> "Profile"
        else -> "Reference"
    }
    val icon get() = when (kind) { "profile" -> "users"; "collection" -> "folder"; else -> "book-open" }
    val body: JsonObject? get() = (metadata?.document?.content as? JsonObject)?.let {
        ((it["document"] as? JsonObject) ?: it).takeUnless { value -> value.isEmpty() }
    }
    val containers get() = metadata?.document?.template?.documentTemplate?.containers.orEmpty().map {
        DocumentContainerOption(it.id, it.name, it.description, it.type.name, it.filters.orEmpty())
    }
    val contentType get() = metadata?.content?.type.orEmpty()
    val contentLength get() = metadata?.content?.length
    val downloadHref get() = metadata?.content?.takeIf { it.length != null }?.urls?.download?.url.orEmpty()
    val imageHref get() = downloadHref.takeIf { contentType.startsWith("image/") }.orEmpty()
    val profileSubtitle get() = profile?.slug?.takeIf(String::isNotBlank)?.let { "@$it" }.orEmpty()
    val visibilityLabel get() = when (profile?.visibility) {
        ProfileVisibility.USER -> "Private"
        ProfileVisibility.PUBLIC -> "Public"
        ProfileVisibility.FRIENDS -> "Friends"
        ProfileVisibility.FRIENDS_OF_FRIENDS -> "Friends of friends"
        ProfileVisibility.SYSTEM -> "System"
        null -> ""
    }
    var items: List<WorkOpsReferenceItem> = emptyList()
        private set
    var hasNext = false
        private set
    val page get() = offset / 25 + 1
    val retryHref get() = workOpsReferenceHref(kind, id, returnTo, offset)
    val previousHref get() = if (offset > 0) workOpsReferenceHref(kind, id, returnTo, (offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (hasNext) workOpsReferenceHref(kind, id, returnTo, offset + 25) else ""

    suspend fun load(ctx: RenderContext) = feedback.perform {
        metadata = null; collection = null; profile = null; items = emptyList(); hasNext = false
        if (kind !in setOf("metadata", "collection", "profile") ||
            !id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) return@perform
        when (kind) {
            "metadata" -> metadata = ctx.gql.execute(WorkOpsReferenceMetadata, WorkOpsReferenceMetadata.Variables(id)).content.metadata
            "profile" -> profile = ctx.gql.execute(WorkOpsReferenceProfile, WorkOpsReferenceProfile.Variables(id)).profiles.profile
            "collection" -> {
                val loaded = ctx.gql.execute(WorkOpsReferenceCollection, WorkOpsReferenceCollection.Variables(id, offset)).content.collections.collection
                collection = loaded
                hasNext = loaded?.items.orEmpty().size > 25
                items = loaded?.items.orEmpty().take(25).map { item ->
                    when (item) {
                        is WorkOpsReferenceCollectionData.Content.Collections.Collection.Items.Metadata ->
                            WorkOpsReferenceItem("metadata", item.id, workOpsContentLabel(item.name, item.id), returnTo)
                        is WorkOpsReferenceCollectionData.Content.Collections.Collection.Items.Collection ->
                            WorkOpsReferenceItem("collection", item.id, item.name.ifBlank { "Untitled collection" }, returnTo)
                        WorkOpsReferenceCollectionData.Content.Collections.Collection.Items.Other -> error("Unsupported collection item.")
                    }
                }
            }
        }
    }
}

/** Keep reference navigation and offset pages within the originating WorkOps workflow. */
fun workOpsReferenceHref(kind: String, id: String, returnTo: String, offset: Long = 0) =
    "/workops/references/${workOpsEncode(kind)}/${workOpsEncode(id)}?returnTo=${workOpsEncode(returnTo)}&offset=$offset"

suspend fun workOpsReference(): WorkOpsReferenceModel {
    val ctx = currentRenderContext()
    return WorkOpsReferenceModel(ctx.params["type"].orEmpty(), ctx.params["id"].orEmpty(),
        ctx.query["returnTo"].orEmpty(), ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also { it.load(ctx) }
}
