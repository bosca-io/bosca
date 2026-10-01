package bosca.cli.data

import bosca.cli.BoscaCliCommand
import bosca.cli.api.ContentApi
import bosca.graphql.gen.DocumentInput
import bosca.graphql.gen.MetadataInput
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.uuid.Uuid

class MetadataCommand : BoscaCliCommand(name = "metadata") {
    override fun help(context: Context) = "Manage content metadata items (documents, guides, data, media)"
    override fun run() = Unit
}

class MetadataListCommand : DataSubcommand("list") {
    override fun help(context: Context) = "List metadata items"

    private val contentType by option("--content-type", help = "Filter by MIME content type")
    private val traitId by option("--trait-id", help = "Filter by trait ID")
    private val offset by option("--offset").int().default(0)
    private val limit by option("--limit").int().default(20)

    override suspend fun execute(api: ContentApi) {
        val items = api.metadata.findMetadata(
            contentTypes = contentType?.let { listOf(it) },
            traitIds = traitId?.let { listOf(it) },
            offset = offset,
            limit = limit,
        )
        for (m in items) {
            echo("${m.id}  ${(m.content?.type ?: "").padEnd(20)}  ${m.name}")
        }
        if (items.isEmpty()) echo("No metadata items found.")
    }
}

class MetadataGetCommand : DataSubcommand("get") {
    override fun help(context: Context) = "Get metadata item details"

    private val id by option("--id", help = "Metadata UUID")
    private val slug by option("--slug", help = "Metadata slug")

    override suspend fun execute(api: ContentApi) {
        // get() and getBySlug() return distinct per-operation Metadata types (the shared interface carries no
        // fields), so handle each source with its own concrete type and print through a common helper.
        val byId = id?.let { api.metadata.get(Uuid.parse(it)) }
        if (byId != null) {
            echoMetadata(byId.name, byId.id.toString(), byId.content?.type, byId.languageTag, byId.version, byId.metadataWorkflow.state, byId.public, byId.attributes)
            return
        }
        val bySlug = slug?.let { api.metadata.getBySlug(it) } ?: error("Provide --id or --slug")
        echoMetadata(bySlug.name, bySlug.id.toString(), bySlug.content?.type, bySlug.languageTag, bySlug.version, bySlug.metadataWorkflow.state, bySlug.public, bySlug.attributes)
    }

    private fun echoMetadata(
        name: String,
        id: String,
        contentType: String?,
        language: String,
        version: Int,
        workflowState: String,
        public: Boolean,
        attributes: kotlinx.serialization.json.JsonElement?,
    ) {
        echo("Metadata: $name")
        echo("  ID: $id")
        echo("  Content Type: $contentType")
        echo("  Language: $language")
        echo("  Version: $version")
        echo("  Workflow State: $workflowState")
        echo("  Public: $public")
        echo("  Attributes: $attributes")
    }
}

class MetadataCreateCommand : DataSubcommand("create") {
    override fun help(context: Context) = "Create a metadata item"

    private val name by option("--name", help = "Display name").required()
    private val contentType by option("--content-type", help = "MIME content type").default("text/html")
    private val languageTag by option("--language-tag", help = "BCP 47 language tag").default("en")
    private val slug by option("--slug", help = "URL-friendly slug")
    private val parentCollectionId by option("--parent-collection-id", help = "Parent collection UUID")
    private val templateId by option("--template-id", help = "Template metadata UUID")
    private val templateVersion by option("--template-version").int()
    private val documentTitle by option("--document-title", help = "Document title")
    private val documentContentFile by option("--document-content-file", help = "Path to ProseMirror JSON file")
    private val attributesJson by option("--attributes-json", help = "Attributes as JSON string")

    override suspend fun execute(api: ContentApi) {
        val json = Json { ignoreUnknownKeys = true }
        val docInput = documentTitle?.let { title ->
            val content = documentContentFile?.let { f ->
                json.decodeFromString<JsonElement>(File(f).readText())
            } ?: JsonObject(emptyMap())
            DocumentInput(
                title = title,
                content = content,
                templateMetadataId = templateId?.let { Uuid.parse(it) },
                templateMetadataVersion = templateVersion ?: 1,
            )
        }
        val metadataInput = MetadataInput(
            name = name,
            contentType = contentType,
            languageTag = languageTag,
            slug = slug,
            parentCollectionId = parentCollectionId?.let { Uuid.parse(it) },
            document = docInput,
            attributes = attributesJson?.let { json.decodeFromString<JsonElement>(it) },
        )
        val id = api.metadata.add(metadataInput) ?: error("Failed to create metadata")
        echo("Created metadata: $id")
    }
}

class MetadataEditCommand : DataSubcommand("edit") {
    override fun help(context: Context) = "Edit a metadata item"

    private val id by option("--id", help = "Metadata UUID").required()
    private val name by option("--name", help = "New display name")
    private val contentType by option("--content-type", help = "New content type")
    private val attributesJson by option("--attributes-json", help = "Attributes as JSON string")

    override suspend fun execute(api: ContentApi) {
        val uuid = Uuid.parse(id)
        val existing = api.metadata.get(uuid) ?: error("Metadata not found: $id")
        val json = Json { ignoreUnknownKeys = true }
        val metadataInput = MetadataInput(
            name = name ?: existing.name,
            contentType = contentType ?: existing.content?.type ?: "text/html",
            languageTag = existing.languageTag,
            attributes = attributesJson?.let { json.decodeFromString<JsonElement>(it) },
        )
        api.metadata.edit(uuid, metadataInput) ?: error("Failed to edit metadata: $id")
        echo("Updated metadata: $id")
    }
}

class MetadataDeleteCommand : DataSubcommand("delete") {
    override fun help(context: Context) = "Delete a metadata item"

    private val id by option("--id", help = "Metadata UUID").required()

    override suspend fun execute(api: ContentApi) {
        api.metadata.delete(Uuid.parse(id))
        echo("Deleted: $id")
    }
}

class MetadataSetContentCommand : DataSubcommand("set-content") {
    override fun help(context: Context) = "Set content on a metadata item (file upload, text, or JSON)"

    private val id by option("--id", help = "Metadata UUID").required()
    private val file by option("--file", help = "Local file path to upload")
    private val text by option("--text", help = "Text content to set")
    private val jsonFile by option("--json-file", help = "Path to JSON content file")
    private val contentType by option("--content-type", help = "MIME content type override")

    override suspend fun execute(api: ContentApi) {
        val uuid = Uuid.parse(id)
        when {
            file != null -> {
                val f = File(file!!)
                if (!f.exists()) error("File not found: ${f.absolutePath}")
                val ct = contentType ?: f.detectContentType()
                api.metadata.setFileContents(uuid, bosca.graphql.client.Upload(f.name, ct, f.readBytes()))
                echo("Uploaded: ${f.name} ($ct)")
            }
            text != null -> {
                val ct = contentType ?: "text/plain"
                api.metadata.setTextContent(uuid, ct, text!!)
                echo("Text content set ($ct)")
            }
            jsonFile != null -> {
                val f = File(jsonFile!!)
                if (!f.exists()) error("File not found: ${f.absolutePath}")
                val ct = contentType ?: "application/json"
                val content = Json.decodeFromString<JsonElement>(f.readText())
                api.metadata.setJsonContent(uuid, ct, content)
                echo("JSON content set ($ct)")
            }
            else -> error("Provide --file, --text, or --json-file")
        }
    }
}

class MetadataSetAttributesCommand : DataSubcommand("set-attributes") {
    override fun help(context: Context) = "Merge attributes into a metadata item"

    private val id by option("--id", help = "Metadata UUID").required()
    private val attributes by option("--attributes", help = "Attributes as JSON string").required()

    override suspend fun execute(api: ContentApi) {
        val json = Json { ignoreUnknownKeys = true }
        api.metadata.mergeAttributes(Uuid.parse(id), json.decodeFromString<JsonElement>(attributes))
        echo("Attributes updated: $id")
    }
}

class MetadataSetReadyCommand : DataSubcommand("set-ready") {
    override fun help(context: Context) = "Mark a metadata item as ready"

    private val id by option("--id", help = "Metadata UUID").required()

    override suspend fun execute(api: ContentApi) {
        api.metadata.setReady(Uuid.parse(id))
        echo("Ready: $id")
    }
}

private fun File.detectContentType(): String = when (extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "pdf" -> "application/pdf"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "txt" -> "text/plain"
    else -> "application/octet-stream"
}
