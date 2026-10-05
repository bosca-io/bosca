package bosca.cli.data

import bosca.cli.BoscaCliCommand
import bosca.cli.api.ContentApi
import bosca.graphql.gen.MetadataSupplementaryInput
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.uuid.Uuid

class SupplementaryCommand : BoscaCliCommand(name = "supplementary") {
    override fun help(context: Context) = "Manage supplementary content (thumbnails, audio, attachments)"
    override fun run() = Unit
}

class SupplementaryListCommand : DataSubcommand("list") {
    override fun help(context: Context) = "List supplementary items for a metadata item"

    private val metadataId by option("--metadata-id", help = "Parent metadata UUID").required()

    override suspend fun execute(api: ContentApi) {
        val supps = api.metadata.getSupplementary(Uuid.parse(metadataId))
        for (s in supps) {
            echo("${s.id}  ${s.key.padEnd(16)}  ${s.content.type.padEnd(20)}  ${s.name}")
        }
        if (supps.isEmpty()) echo("No supplementary items found.")
    }
}

class SupplementaryAddCommand : DataSubcommand("add") {
    override fun help(context: Context) = "Add supplementary content to a metadata item"

    private val metadataId by option("--metadata-id", help = "Parent metadata UUID").required()
    private val key by option("--key", help = "Unique key (e.g. thumbnail, audio)").required()
    private val name by option("--name", help = "Display name").required()
    private val contentType by option("--content-type", help = "MIME content type").required()
    private val file by option("--file", help = "Local file path to upload")
    private val text by option("--text", help = "Text content to set")
    private val attributesJson by option("--attributes-json", help = "Attributes as JSON")

    override suspend fun execute(api: ContentApi) {
        val uuid = Uuid.parse(metadataId)
        val json = Json { ignoreUnknownKeys = true }
        val input = MetadataSupplementaryInput(
            metadataId = uuid,
            key = key,
            name = name,
            contentType = contentType,
            attributes = attributesJson?.let { json.decodeFromString<JsonObject>(it) },
            contentLength = file?.let { File(it).length().toInt() },
            planId = uuid,
        )
        val supp = api.metadata.addSupplementary(input) ?: error("Failed to add supplementary")

        file?.let { path ->
            val f = File(path)
            if (f.exists()) {
                api.metadata.setSupplementaryContents(supp.id, f, contentType)
                echo("Uploaded: ${f.name}")
            } else {
                echo("WARNING: File not found: ${f.absolutePath}")
            }
        }

        text?.let { t ->
            api.metadata.setSupplementaryTextContent(supp.id, contentType, t)
        }

        echo("Created supplementary: ${supp.id} (key=$key)")
    }
}

class SupplementaryDeleteCommand : DataSubcommand("delete") {
    override fun help(context: Context) = "Delete a supplementary item"

    private val supplementaryId by option("--supplementary-id", help = "Supplementary UUID").required()

    override suspend fun execute(api: ContentApi) {
        api.metadata.deleteSupplementary(Uuid.parse(supplementaryId))
        echo("Deleted: $supplementaryId")
    }
}

class SupplementarySetContentCommand : DataSubcommand("set-content") {
    override fun help(context: Context) = "Set content on a supplementary item"

    private val supplementaryId by option("--supplementary-id", help = "Supplementary UUID").required()
    private val file by option("--file", help = "Local file path to upload")
    private val text by option("--text", help = "Text content")
    private val contentType by option("--content-type", help = "MIME content type")

    override suspend fun execute(api: ContentApi) {
        val uuid = Uuid.parse(supplementaryId)
        when {
            file != null -> {
                val f = File(file!!)
                if (!f.exists()) error("File not found: ${f.absolutePath}")
                val ct = contentType ?: detectContentType(f)
                api.metadata.setSupplementaryContents(uuid, f, ct)
                echo("Uploaded: ${f.name} ($ct)")
            }
            text != null -> {
                val ct = contentType ?: "text/plain"
                api.metadata.setSupplementaryTextContent(uuid, ct, text!!)
                echo("Text content set ($ct)")
            }
            else -> error("Provide --file or --text")
        }
    }
}

private fun detectContentType(file: File): String = when (file.extension.lowercase()) {
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
