package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.design.model.DocumentContainerOption
import bosca.workops.ui.graphql.DocumentInput
import bosca.workops.ui.graphql.WorkOpsDocumentMetadata
import bosca.workops.ui.graphql.WorkOpsDocumentMetadataData
import bosca.workops.ui.graphql.WorkOpsSaveDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** WorkOps owns Content reads and saves; the Design Library owns the shared editor. */
@Serializable
class WorkOpsDocumentModel(
    val metadataId: String,
    var data: WorkOpsDocumentMetadataData? = null,
    var savedRevision: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val metadata get() = data?.content?.metadata?.takeIf { it.document != null }
    val canEdit get() = metadataId in data?.content?.editable.orEmpty()
    val room get() = metadata?.let { "${it.id}.${it.version}" }.orEmpty()
    val collaborator get() = data?.profiles?.current.orEmpty().firstOrNull()?.name ?: "Editor"
    val contentExpression get() = (metadata?.document?.template?.documentTemplate?.schema as? JsonPrimitive)
        ?.contentOrNull?.takeIf(String::isNotBlank) ?: "heading block+"
    val containers get() = metadata?.document?.template?.documentTemplate?.containers.orEmpty().map {
        DocumentContainerOption(it.id, it.name, it.description, it.type.name, it.filters.orEmpty())
    }
    val body: JsonObject get() {
        val content = metadata?.document?.content
        val document = (content as? JsonObject)?.get("document") ?: content
        return (document as? JsonObject)?.takeIf { (it["content"] as? JsonArray)?.isNotEmpty() == true } ?: buildJsonObject {
            put("type", "doc")
            (document as? JsonObject)?.get("attrs")?.let { put("attrs", it) }
            putJsonArray("content") {
                addJsonObject {
                    put("type", "heading"); putJsonObject("attrs") { put("level", 1) }
                    val title = workOpsContentLabel(metadata?.document?.title.orEmpty(), metadataId, "")
                    if (title.isNotEmpty()) putJsonArray("content") { addJsonObject { put("type", "text"); put("text", title) } }
                }
                addJsonObject { put("type", "paragraph") }
            }
        }
    }

    suspend fun reload(ctx: RenderContext) = feedback.perform {
        val loaded = ctx.gql.execute(WorkOpsDocumentMetadata, WorkOpsDocumentMetadata.Variables(metadataId))
        data = loaded
        require(metadata != null) { "The document is unavailable." }
    }

    suspend fun save(document: String, revision: String, ctx: RenderContext) = feedback.perform {
        val metadata = requireNotNull(metadata) { "Reload the document before saving." }
        require(canEdit) { "You do not have permission to edit this document." }
        val body = Json.parseToJsonElement(document).jsonObject
        require(body["type"] == JsonPrimitive("doc")) { "Expected a document body." }
        val captured = Json.parseToJsonElement(revision).jsonObject
        require(captured.values.all { it is JsonPrimitive && !it.isString && it.longOrNull?.let { number -> number in 0..9_007_199_254_740_991L } == true }) { "Invalid document save revision." }
        fun text(node: JsonObject): String = if (node["type"] == JsonPrimitive("mention")) {
            "@" + ((node["attrs"] as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull
                .orEmpty().ifEmpty { "Mention" }
        } else (node["text"] as? JsonPrimitive)?.contentOrNull
            ?: (node["content"] as? JsonArray).orEmpty().joinToString("") { text(it.jsonObject) }
        fun heading(node: JsonObject): JsonObject? {
            if (node["type"] == JsonPrimitive("heading") && (node["attrs"] as? JsonObject)?.get("level") == JsonPrimitive(1)) return node
            return (node["content"] as? JsonArray).orEmpty().firstNotNullOfOrNull { heading(it.jsonObject) }
        }
        val template = metadata.document?.template
        val input = DocumentInput(
            title = heading(body)?.let(::text) ?: metadata.document?.title.orEmpty(),
            content = buildJsonObject { put("document", body) },
            templateMetadataId = template?.id, templateMetadataVersion = template?.version,
        )
        check(ctx.gql.execute(WorkOpsSaveDocument, WorkOpsSaveDocument.Variables(metadata.id, metadata.version, input))
            .content.metadata.setMetadataDocument) { "The document was not saved." }
        savedRevision = revision
        feedback.message = "Document saved."
    }
}
