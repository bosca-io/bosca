package bosca.content.transformations

import bosca.bible.Reference
import bosca.content.find.FindQueryInput
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.LocaleAwareDocument
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.documents.BulletListNode
import bosca.documents.ContainerNode
import bosca.documents.Content
import bosca.documents.DocumentNode
import bosca.documents.HeadingNode
import bosca.documents.ListItemNode
import bosca.documents.OrderedListNode
import bosca.documents.ParagraphNode
import bosca.documents.TaskItemNode
import bosca.documents.TaskListNode
import bosca.documents.TextNode
import bosca.search.IndexStorageSystem
import bosca.transformations.Transformation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@Serializable
data class DocumentToTextConfiguration(
    val includeTitle: Boolean = true,
    val includeTtsMarkup: Boolean = false,
    val excludeContainers: Set<String> = emptySet()
)

class MetadataDocumentToTextTransformation(
    private val metadataService: MetadataService,
    private val documentService: DocumentService,
    private val bibleService: BibleService,
    private val json: Json,
    private val configuration: DocumentToTextConfiguration = DocumentToTextConfiguration()
) : Transformation<IndexStorageSystem, Metadata, String> {

    override suspend fun transform(context: IndexStorageSystem, item: Metadata): String {
        try {
            val document = documentService.getDocument(item.id, item.version)
            return document?.asText(TextContext(json, configuration, metadataService, bibleService, Locale.forLanguageTag(item.languageTag))) ?: ""
        } catch (e: Exception) {
            log.error("error: failed to parse document", e)
            return ""
        }
    }
}

class IndexableDocumentToTextTransformation(
    private val metadataService: MetadataService,
    private val bibleService: BibleService,
    private val json: Json,
    private val configuration: DocumentToTextConfiguration = DocumentToTextConfiguration()
) : Transformation<IndexStorageSystem, LocaleAwareDocument, String> {

    override suspend fun transform(context: IndexStorageSystem, item: LocaleAwareDocument): String {
        return item.document.asText(TextContext(json, configuration, metadataService, bibleService, item.locale))
    }
}

class DocumentToTextTransformation(
    private val metadataService: MetadataService,
    private val bibleService: BibleService,
    private val json: Json,
    private val configuration: DocumentToTextConfiguration = DocumentToTextConfiguration()
) : Transformation<Unit, LocaleAwareDocument, String> {

    override suspend fun transform(context: Unit, item: LocaleAwareDocument): String {
        return item.document.asText(TextContext(json, configuration, metadataService, bibleService, item.locale))
    }
}

class DocumentReferencesToListTransformation(
    private val metadataService: MetadataService,
    private val documentService: DocumentService,
    private val json: Json,
) : Transformation<IndexStorageSystem, Metadata, List<Reference>> {

    override suspend fun transform(context: IndexStorageSystem, item: Metadata): List<Reference> {
        val references = getReferences(item)
        if (references.isEmpty() && item.parentId != null) {
            val parent = metadataService.getById(item.parentId ?: error("missing id")) ?: return emptyList()
            return getReferences(parent)
        }
        return references
    }

    private suspend fun getReferences(item: Metadata): List<Reference> {
        try {
            val document = documentService.getDocument(item.id, item.version)
            val references = mutableListOf<Reference>()
            document?.content?.let { content ->
                try {
                    content.document.content.forEach { node ->
                        if (node is ContainerNode && node.attributes.name == "BIBLE_REFERENCES") {
                            node.attributes.references?.forEach {
                                references.add(Reference(it))
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error("error: failed to parse document", e)
                }
            }
            return references
        } catch (e: Exception) {
            log.error("error: failed to get document", e)
        }
        return emptyList()
    }

    companion object {

        private val log = LoggerFactory.getLogger(DocumentReferencesToListTransformation::class.java)
    }
}

data class References(
    val locale: Locale,
    val references: List<Reference>
)

class ReferencesListToBookListTransformation(
    private val bibleService: BibleService,
    private val metadataService: MetadataService,
) : Transformation<IndexStorageSystem, References, List<String>> {

    override suspend fun transform(context: IndexStorageSystem, item: References): List<String> {
        val metadatas = metadataService.find(
            FindQueryInput(
                contentTypes = listOf("bosca/v-bible"),
                languageTags = listOf(item.locale.isO3Language, item.locale.language),
                offset = 0,
                limit = 1
            )
        )
        if (metadatas.isEmpty()) return emptyList()
        val metadata = metadatas.first()
        val bible = bibleService.getBible(metadata.id, metadata.version, null) ?: return emptyList()
        val books = bibleService.getBooks(bible).associateBy { it.usfm }
        val bookNames = mutableSetOf<String>()
        for (ref in item.references) {
            val book = books[ref.bookUsfm] ?: continue
            bookNames.add(book.nameLong ?: book.nameShort ?: book.abbreviation)
        }
        return bookNames.toList()
    }
}

private fun DocumentNode.filterTitle(title: Boolean): List<DocumentNode> {
    var first = true
    return content.filter {
        if (it is HeadingNode && it.attributes.level == 1 && first) {
            first = false
            title
        } else {
            !title
        }
    }
}

private class TextContext(
    val json: Json,
    val configuration: DocumentToTextConfiguration,
    val metadataService: MetadataService,
    val bibleService: BibleService,
    val locale: Locale
)

@OptIn(ExperimentalAtomicApi::class)
private suspend fun Document.asText(context: TextContext): String {
    return content?.asText(context) ?: ""
}

@OptIn(ExperimentalAtomicApi::class)
private suspend fun Content.asText(context: TextContext): String {
    val string = StringBuilder()
    val length = AtomicLong(0)
    document.let {
        if (!context.configuration.includeTitle) {
            val nodes = it.filterTitle(false)
            for (node in nodes) {
                append(node, context, string, length)
            }
        } else if (context.configuration.includeTtsMarkup) {
            val title = it.filterTitle(true).firstOrNull()
            title?.let {
                append(it, context, string, length)
            }
            val nodes = it.filterTitle(false)
            for (node in nodes) {
                append(node, context, string, length)
            }
        } else {
            append(it, context, string, length)
        }
        string.append("\n")
    }
    return string.toString().trim()
}

@OptIn(ExperimentalAtomicApi::class)
private fun append(component: JsonElement, context: TextContext, key: String?, builder: StringBuilder, length: AtomicLong) {
    when (component) {
        is JsonObject -> component.entries.forEach { entry -> append(entry.value, context, entry.key, builder, length) }
        is JsonArray -> component.forEach { append(it, context, key, builder, length) }
        is JsonPrimitive if key == "text" -> {
            builder.appendText(component.content, context, length)
            builder.appendText(" ", context, length)
        }

        else -> {}
    }
}

@OptIn(ExperimentalAtomicApi::class)
private fun StringBuilder.appendText(text: String, context: TextContext, length: AtomicLong) {
    if (context.configuration.includeTtsMarkup) {
        val len = length.addAndFetch(text.toByteArray().size.toLong())
        if (len > 4000) {
            length.store(0)
            append("[split]")
        }
    }
    append(text)
}

@OptIn(ExperimentalAtomicApi::class)
private suspend fun append(node: DocumentNode, context: TextContext, builder: StringBuilder, length: AtomicLong) {
    if (node is ParagraphNode) {
        builder.appendText("\n", context, length)
    }
    if (node is OrderedListNode || node is BulletListNode || node is ListItemNode || node is TaskListNode || node is TaskItemNode) {
        builder.appendText("\n", context, length)
    }
    if (node is TextNode) {
        builder.appendText(node.text.replace("’", "'"), context, length)
    }
    try {
        if (node is ContainerNode) {
            if (node.attributes.name != null && context.configuration.excludeContainers.contains(node.attributes.name)) return
            if (node.attributes.metadataId != null && node.attributes.references?.isNotEmpty() == true) {
                for (reference in node.attributes.references) {
                    val reference = Reference(reference)
                    var metadata = context.metadataService.getById(node.attributes.metadataId ?: error("missing id"))?.takeIf {
                        it.languageTag == context.locale.language || it.languageTag == context.locale.isO3Language
                    }
                    if (metadata == null) {
                        metadata = context.metadataService.find(
                            FindQueryInput(
                                contentTypes = listOf("bosca/v-bible"),
                                languageTags = listOf(context.locale.isO3Language, context.locale.language),
                            )
                        ).firstOrNull()
                    }
                    if (metadata != null) {
                        val bible = context.bibleService.getBible(metadata.id, metadata.version, null) ?: continue
                        val content = context.bibleService.getChapter(bible, reference)
                        builder.appendText(context.bibleService.getHuman(bible, reference), context, length)
                        if (context.configuration.includeTtsMarkup) {
                            builder.append(" [pause] ")
                        }
                        builder.appendText("\n\n", context, length)
                        content.components?.let {
                            append(it, context, null, builder, length)
                        }
                    }
                    if (context.configuration.includeTtsMarkup) {
                        builder.appendText(" [pause] ", context, length)
                    }
                }
            }
        }
    } catch (e: Exception) {
        log.error("error: failed to get bible chapter: ${e.message}", e)
    }
    for (child in node.content) {
        append(child, context, builder, length)
    }
    if (node is OrderedListNode || node is BulletListNode || node is TaskListNode) {
        builder.appendText("\n", context, length)
    }
    if (node is ParagraphNode) {
        if (context.configuration.includeTtsMarkup) {
            builder.appendText(" [pause] ", context, length)
        }
        builder.appendText("\n\n", context, length)
    }
    if (node is HeadingNode) {
        if (context.configuration.includeTtsMarkup) {
            builder.appendText(" [pause] ", context, length)
        }
        builder.appendText("\n\n\n", context, length)
    }
}

private val log = LoggerFactory.getLogger(DocumentToTextTransformation::class.java)