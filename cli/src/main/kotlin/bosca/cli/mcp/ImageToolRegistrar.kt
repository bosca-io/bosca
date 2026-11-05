package bosca.cli.mcp

import bosca.cli.api.ContentImages
import bosca.graphql.gen.AttachContentImageData
import bosca.graphql.gen.ImageCropInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.Base64
import kotlin.uuid.Uuid

/** Image operations use Bosca's configured provider and content permissions. */
object ImageToolRegistrar {
    val tool = Tool(
        name = "content_image",
        description = "Generate an image, edit a stored image, get its preview, or attach it through a content template. Generate and edit store a NEW content item; the source is unchanged. " +
            "Use sourceId for edit and id for get/attach. Supply a destination parentCollectionId when requested. " +
            "Generate and edit require content creation access; edit also requires source content view access. Results contain a content link and an image preview when readable. " +
            "Generation can incur provider charges. Call only for requested image work; never automatically retry an uncertain generate/edit. " +
            "A preview failure after generation still returns the saved image ID. Use get to retry only the read. For attach, set exactly one targetMetadataId or targetCollectionId. Omit attributeKey to select the sole image attribute or discover choices. Single-image attributes replace the existing image; list attributes preserve other images. targetLanguageTag selects a collection variant, and crop uses source pixels. Attachment requires source VIEW and target EDIT and does not publish.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("action") { put("type", "string"); put("enum", JsonArray(listOf("generate", "edit", "get", "attach").map(::JsonPrimitive))) }
            for (key in listOf("prompt", "name", "sourceId", "id", "parentCollectionId", "targetMetadataId", "targetCollectionId", "targetLanguageTag", "attributeKey")) putJsonObject(key) { put("type", "string") }
            putJsonObject("crop") {
                put("type", "object")
                putJsonObject("properties") { for (key in listOf("top", "left", "width", "height")) putJsonObject(key) { put("type", "number") } }
                put("required", JsonArray(listOf("top", "left", "width", "height").map(::JsonPrimitive)))
            }
        }, required = listOf("action")),

        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true),
    )

    fun registerAll(server: Server, api: ContentImages) {
        server.addTool(tool) { request -> handle(api, request.arguments ?: error("Missing arguments")) }
    }

    internal suspend fun handle(api: ContentImages, args: Map<String, JsonElement>): CallToolResult = try {
        if (args.required("action") == "attach") attach(api, args) else image(api, args)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { CallToolResult(content = listOf(TextContent("Error: ${failure.message}")), isError = true) }

    private suspend fun image(api: ContentImages, args: Map<String, JsonElement>): CallToolResult {
        val action = args.required("action")
        val parent = args.string("parentCollectionId")?.let(Uuid::parse)
        val image = when (action) {
            "generate" -> api.generate(args.required("prompt"), args.string("name") ?: "Generated Image", parent)
            "edit" -> api.edit(Uuid.parse(args.required("sourceId")), args.required("prompt"), args.string("name") ?: "Edited Image", parent)
            "get" -> requireNotNull(api.get(Uuid.parse(args.required("id")))) { "Image not found or unavailable" }
            else -> error("Unknown image action")
        }
        val content = image.content
        require(content == null || content.type.startsWith("image/")) { "Content item is not an image" }
        val blocks = mutableListOf<ContentBlock>(TextContent(buildJsonObject {
            put("id", image.id.toString()); put("version", image.version); put("name", image.name)
            put("contentType", content?.type); put("contentLength", content?.length)
            put("uploaded", image.uploaded?.toString())
        }.toString()), ResourceLink(name = image.name, uri = "bosca://content/metadata/${image.id}", mimeType = content?.type))
        try {
            check(image.uploaded != null) { "Image upload is incomplete" }
            val mimeType = content?.type ?: error("Image content is unavailable")
            blocks += ImageContent(data = Base64.getEncoder().encodeToString(api.preview(image.id)), mimeType = mimeType)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            blocks += TextContent("Image ${image.id} is stored, but its preview is unavailable: ${failure.message}. Use get to retry the preview.")
        }
        return CallToolResult(content = blocks)
    }

    private suspend fun attach(api: ContentImages, args: Map<String, JsonElement>): CallToolResult {
        val metadata = args.string("targetMetadataId")?.let(Uuid::parse)
        val collection = args.string("targetCollectionId")?.let(Uuid::parse)
        require((metadata == null) != (collection == null)) { "Set exactly one attachment target" }
        val language = args.string("targetLanguageTag")
        require(metadata == null || language.isNullOrBlank()) { "targetLanguageTag is only valid with targetCollectionId" }
        val crop = args["crop"]?.takeUnless { it == JsonNull }?.let { Json.decodeFromJsonElement(ImageCropInput.serializer(), it) }
        val result = api.attach(Uuid.parse(args.required("id")), metadata, collection, language, args.string("attributeKey"), crop)
        val attachment = result.content.images.attach
        val blocks = mutableListOf<ContentBlock>(TextContent(Json.encodeToString(AttachContentImageData.serializer(), result)))
        if (attachment.attached) {
            blocks += ResourceLink(name = "Image attachment", uri = if (metadata != null) "bosca://content/metadata/$metadata" else "bosca://content/collections/$collection")
        }
        return CallToolResult(content = blocks, isError = !attachment.success)
    }

    private fun Map<String, JsonElement>.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun Map<String, JsonElement>.required(key: String) = requireNotNull(string(key)?.takeIf(String::isNotBlank)) { "Missing $key" }
}
