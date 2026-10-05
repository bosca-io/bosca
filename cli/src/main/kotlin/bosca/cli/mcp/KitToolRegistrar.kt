package bosca.cli.mcp

import bosca.cli.api.KitApi
import bosca.cli.api.KitAsset
import bosca.cli.api.KitChatMessage
import bosca.cli.api.KitChatSession
import bosca.cli.api.KitImageReference
import bosca.cli.api.displayEventOrNull
import bosca.cli.api.responseContent
import bosca.cli.api.textContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Exposes Kit's durable chat API as a synchronous, model-friendly MCP surface. */
object KitToolRegistrar {

    internal val chatTool = Tool(
        name = "kit_chat",
        description = """
            Talk to Kit, Bosca's AI assistant. Use action `ask` for natural-language requests such as
            analytics questions, image generation/editing, content work, or general Bosca questions.
            Omit sessionId to start a conversation; reuse the returned sessionId for follow-up turns.
            The call waits for Kit's response and returns analytics display events as structured data.
            Generated images are returned as MCP image content by default. When the user asks to save
            them locally, pass downloadDirectory; files are written there using their metadata UUID.
            Other actions: list, get, delete.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                enumProperty("action", "Operation to perform", "ask", "list", "get", "delete")
                stringProperty("message", "Natural-language message for Kit (ask)")
                stringProperty("sessionId", "Existing Kit chat session UUID (ask/get/delete)")
                stringProperty("title", "Title for a new conversation (ask without sessionId)")
                integerProperty("timeoutSeconds", "How long to wait for Kit, 1-900 seconds (default 300)")
                integerProperty("limit", "Maximum sessions returned by list (default 20, max 200)")
                booleanProperty("includeImages", "Return generated images as MCP image content (default true)")
                stringProperty(
                    "downloadDirectory",
                    "Absolute or relative local directory in which to save generated images",
                )
            },
            required = listOf("action"),
        ),
        annotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false,
            openWorldHint = true,
        ),
    )

    internal val downloadTool = Tool(
        name = "kit_download",
        description = """
            Download an image or other content item produced by Kit to an explicit local file path.
            Use the metadataId returned by kit_chat. Existing files are protected unless overwrite=true.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringProperty("metadataId", "Bosca metadata UUID returned for a generated asset")
                stringProperty("outputPath", "Exact local destination file path")
                booleanProperty("overwrite", "Replace an existing file (default false)")
            },
            required = listOf("metadataId", "outputPath"),
        ),
        annotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false,
            openWorldHint = false,
        ),
    )

    fun registerAll(server: Server, api: KitApi) {
        server.addTool(chatTool) { request ->
            result { handleChat(api, request.arguments ?: error("Missing arguments")) }
        }
        server.addTool(downloadTool) { request ->
            result { handleDownload(api, request.arguments ?: error("Missing arguments")) }
        }
    }

    internal suspend fun handleChat(api: KitApi, args: Map<String, JsonElement>): CallToolResult =
        when (val action = args.str("action")) {
            "ask" -> ask(api, args)
            "list" -> {
                val sessions = api.listSessions().take((args.optInt("limit") ?: 20).coerceIn(1, 200))
                val structured = buildJsonObject {
                    put("sessions", JsonArray(sessions.map { it.summaryJson() }))
                }
                CallToolResult(content = listOf(TextContent(structured.toString())), structuredContent = structured)
            }
            "get" -> {
                val session = api.getSession(Uuid.parse(args.str("sessionId")))
                    ?: error("Kit session not found")
                require(session.agentKey == KitApi.KIT_AGENT_KEY) { "The requested session does not belong to Kit" }
                val structured = buildJsonObject { put("session", session.detailJson()) }
                CallToolResult(content = listOf(TextContent(structured.toString())), structuredContent = structured)
            }
            "delete" -> {
                val id = Uuid.parse(args.str("sessionId"))
                val session = api.getSession(id) ?: error("Kit session not found: $id")
                require(session.agentKey == KitApi.KIT_AGENT_KEY) { "The requested session does not belong to Kit" }
                check(!session.processing) { "Cannot delete Kit session $id while it is processing" }
                val structured = buildJsonObject {
                    put("sessionId", id.toString())
                    put("deleted", api.deleteSession(id))
                }
                CallToolResult(content = listOf(TextContent(structured.toString())), structuredContent = structured)
            }
            else -> error("Unknown kit_chat action: $action")
        }

    internal suspend fun handleDownload(api: KitApi, args: Map<String, JsonElement>): CallToolResult {
        val metadataId = Uuid.parse(args.str("metadataId"))
        val asset = api.downloadAsset(metadataId)
        val output = Path.of(args.str("outputPath")).toAbsolutePath().normalize()
        writeAsset(asset, output, args.optBool("overwrite") == true)

        val structured = buildJsonObject {
            put("metadataId", metadataId.toString())
            put("path", output.toString())
            put("contentType", asset.contentType)
            put("contentLength", asset.bytes.size)
        }
        return CallToolResult(
            content = listOf(
                TextContent(structured.toString()),
                ResourceLink(
                    name = output.fileName.toString(),
                    uri = output.toUri().toString(),
                    title = "Kit-generated asset",
                    size = asset.bytes.size.toLong(),
                    mimeType = asset.contentType,
                ),
            ),
            structuredContent = structured,
        )
    }

    private suspend fun ask(api: KitApi, args: Map<String, JsonElement>): CallToolResult {
        val timeoutSeconds = args.optInt("timeoutSeconds") ?: 300
        require(timeoutSeconds in 1..900) { "timeoutSeconds must be between 1 and 900" }
        val turn = api.ask(
            message = args.str("message"),
            sessionId = args.optStr("sessionId")?.let(Uuid::parse),
            title = args.optStr("title"),
            timeoutMillis = timeoutSeconds * 1_000L,
        )

        val response = turn.responseContent()
        val responseText = response.text
        val displayEvents = response.displayEvents
        val references = response.images
        val includeImages = args.optBool("includeImages") != false
        val downloadDirectory = args.optStr("downloadDirectory")?.let { raw ->
            Path.of(raw).toAbsolutePath().normalize().also(::ensureDirectory)
        }
        val preparedImages = references.map { reference ->
            prepareImage(api, reference, includeImages, downloadDirectory)
        }

        val failedStatus = turn.session.status.name == "FAILED"
        val errorText = preparedImages.mapNotNull { it.error }.joinToString("\n")
        val visibleText = buildString {
            append(responseText.ifBlank {
                if (failedStatus) "Kit failed to complete the request." else "Kit completed the request."
            })
            if (errorText.isNotBlank()) append("\n\nImage retrieval errors:\n$errorText")
        }
        val structured = buildJsonObject {
            put("sessionId", turn.session.id.toString())
            put("title", turn.session.title)
            put("status", turn.session.status.name)
            put("response", responseText)
            put("messages", JsonArray(turn.messages.map { it.toJson() }))
            put("displayEvents", JsonArray(displayEvents))
            put("images", JsonArray(preparedImages.map { it.toJson() }))
        }
        val content = buildList<ContentBlock> {
            add(TextContent(visibleText))
            preparedImages.forEach { image ->
                image.asset?.takeIf { includeImages && it.contentType.startsWith("image/") }?.let { asset ->
                    add(ImageContent(Base64.getEncoder().encodeToString(asset.bytes), asset.contentType))
                }
                image.path?.let { path ->
                    add(
                        ResourceLink(
                            name = path.fileName.toString(),
                            uri = path.toUri().toString(),
                            title = image.reference.alt.ifBlank { "Kit-generated image" },
                            size = image.asset?.bytes?.size?.toLong(),
                            mimeType = image.asset?.contentType,
                        ),
                    )
                }
            }
        }
        val failed = failedStatus ||
            (responseText.isBlank() && displayEvents.isEmpty() && references.isEmpty())
        return CallToolResult(content = content, isError = failed.takeIf { it }, structuredContent = structured)
    }

    private suspend fun prepareImage(
        api: KitApi,
        reference: KitImageReference,
        includeImage: Boolean,
        downloadDirectory: Path?,
    ): PreparedImage {
        if (!includeImage && downloadDirectory == null) return PreparedImage(reference)
        return try {
            val asset = api.downloadAsset(reference.metadataId)
            require(asset.contentType.startsWith("image/")) {
                "Metadata ${reference.metadataId} is ${asset.contentType}, not an image"
            }
            val path = downloadDirectory?.resolve("${reference.metadataId}.${extension(asset.contentType)}")
            if (path != null) writeAsset(asset, path, overwrite = false)
            PreparedImage(reference, asset, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PreparedImage(reference, error = "${reference.metadataId}: ${e.message}")
        }
    }

    private fun KitChatSession.summaryJson(): JsonObject = buildJsonObject {
        put("id", id.toString())
        put("agentKey", agentKey)
        put("title", title)
        put("status", status.name)
        put("processing", processing)
        put("created", created.toString())
        put("modified", modified.toString())
    }

    private fun KitChatSession.detailJson(): JsonObject = buildJsonObject {
        summaryJson().forEach(::put)
        put("messages", JsonArray(messages.map { it.toJson() }))
    }

    private fun KitChatMessage.toJson(): JsonObject = buildJsonObject {
        put("id", id.toString())
        put("sessionId", sessionId.toString())
        put("author", author)
        put("created", created.toString())
        put("text", textContent())
        val eventObject = event.displayEventOrNull()
        if (eventObject != null) {
            put("event", eventObject)
        } else {
            put("event", JsonNull)
        }
    }

    private fun PreparedImage.toJson(): JsonObject = buildJsonObject {
        put("metadataId", reference.metadataId.toString())
        put("alt", reference.alt)
        put("downloadUrl", reference.url)
        put("contentType", asset?.contentType)
        put("contentLength", asset?.bytes?.size)
        put("path", path?.toString())
        put("error", error)
    }

    private fun ensureDirectory(path: Path) {
        if (Files.exists(path) && !Files.isDirectory(path)) error("Download path is not a directory: $path")
        Files.createDirectories(path)
    }

    private suspend fun writeAsset(asset: KitAsset, path: Path, overwrite: Boolean) = withContext(Dispatchers.IO) {
        path.parent?.let(Files::createDirectories)
        if (!overwrite && Files.exists(path)) error("File already exists: $path")
        val options = if (overwrite) {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        } else {
            arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
        Files.write(path, asset.bytes, *options)
    }

    private suspend fun result(block: suspend () -> CallToolResult): CallToolResult =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }

    private fun Map<String, JsonElement>.str(key: String): String =
        get(key)?.jsonPrimitive?.content ?: error("Missing required field: $key")

    private fun Map<String, JsonElement>.optStr(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun Map<String, JsonElement>.optInt(key: String): Int? =
        get(key)?.jsonPrimitive?.intOrNull

    private fun Map<String, JsonElement>.optBool(key: String): Boolean? =
        get(key)?.jsonPrimitive?.booleanOrNull

    private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, description: String) {
        put(name, buildJsonObject {
            put("type", "string")
            put("description", description)
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.integerProperty(name: String, description: String) {
        put(name, buildJsonObject {
            put("type", "integer")
            put("description", description)
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.booleanProperty(name: String, description: String) {
        put(name, buildJsonObject {
            put("type", "boolean")
            put("description", description)
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.enumProperty(
        name: String,
        description: String,
        vararg values: String,
    ) {
        put(name, buildJsonObject {
            put("type", "string")
            put("description", description)
            put("enum", JsonArray(values.map(::JsonPrimitive)))
        })
    }

    private fun extension(contentType: String): String = when (contentType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/svg+xml" -> "svg"
        else -> "bin"
    }

    private data class PreparedImage(
        val reference: KitImageReference,
        val asset: KitAsset? = null,
        val path: Path? = null,
        val error: String? = null,
    )

}
