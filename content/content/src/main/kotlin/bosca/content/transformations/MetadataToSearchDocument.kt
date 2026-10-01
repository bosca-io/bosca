package bosca.content.transformations

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.content.metadata.service.DataService
import bosca.search.IndexStorageSystem
import bosca.search.model.MetadataSearchContext
import bosca.search.model.SearchDocumentItem
import bosca.search.model.SearchTransformConfiguration
import bosca.slug.service.SlugService
import bosca.serialization.JsonConverter.toAny
import bosca.serialization.JsonConverter.toJsonElement
import bosca.storage.service.ObjectStorageService
import bosca.transformations.Transformation
import com.dashjoin.jsonata.Jsonata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.*

interface MetadataToSearchCollections {

    suspend fun toCollections(metadataService: MetadataService, metadata: Metadata): Map<String, List<SearchDocumentItem>>
}

interface MetadataToSearchAttributes {

    suspend fun toAttributes(metadataService: MetadataService, metadata: Metadata): JsonObject?
}

class MetadataToSearchDocumentConfiguration(
    val collections: MetadataToSearchCollections,
    documentService: DocumentService,
    guideService: GuideService,
    dataService: DataService,
    val attributes: MetadataToSearchAttributes = DefaultMetadataToSearchAttributes(documentService, guideService, dataService),
    val excludeTypes: Set<String> = emptySet(),
    val excludeContentTypePrefix: List<String> = emptyList()
)

class MetadataToSearchDocument(
    private val metadataService: MetadataService,
    private val documentToText: Transformation<IndexStorageSystem, Metadata, String>,
    private val storage: ObjectStorageService,
    private val slugs: SlugService,
    private val json: Json,
    private val configuration: MetadataToSearchDocumentConfiguration,
    private val documentReferences: DocumentReferencesToListTransformation,
    private val referencesToBookList: ReferencesListToBookListTransformation,
    private val configurationService: ConfigurationService,
) : Transformation<IndexStorageSystem, Metadata, JsonElement?> {

    override suspend fun transform(context: IndexStorageSystem, item: Metadata): JsonElement? {
        if (context.name != "Admin Search Index") {
            val type = item.attributes?.takeIf { it is JsonObject }?.jsonObject?.get("type")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            if (type != null && type in configuration.excludeTypes) return null
            if (configuration.excludeContentTypePrefix.any { item.contentType.startsWith(it) }) return null
        }
        val result = toContext(context, item)
        val searchConfiguration = configurationService.getValueAs<SearchTransformConfiguration>("search", json)
        val expression = searchConfiguration?.expressions?.metadata
        if (expression.isNullOrBlank()) return json.encodeToJsonElement(result)
        return Jsonata.jsonata(expression).evaluate(json.encodeToJsonElement(result).toAny()).toJsonElement()
    }

    /**
     * Extracts the plain body text of [item] for indexing/embedding: raw storage bytes for `text/\*`
     * content, otherwise the document-to-text transformation. Best-effort — returns "" (never throws)
     * when the body can't be retrieved, so a storage/parse hiccup never fails indexing. Shared by
     * [toContext] and the embedding pipeline node so both operate on identical text.
     */
    suspend fun extractText(context: IndexStorageSystem, item: Metadata): String =
        (if (item.contentType.startsWith("text/")) {
            val path = storage.getPath(item)
            try {
                storage.getInputStream(path).use { it.readAllBytes() }.decodeToString()
            } catch (e: Exception) {
                log.error("Failed to retrieve text from storage", e)
                null
            }
        } else {
            try {
                documentToText.transform(context, item)
            } catch (e: Exception) {
                log.error("Failed to transform text", e)
                null
            }
        }) ?: ""

    suspend fun toContext(context: IndexStorageSystem, item: Metadata): MetadataSearchContext {
        val content = extractText(context, item)
        val relationships = metadataService.getRelationships(item.id)
        val relationshipMap = mutableMapOf<String, MutableList<MetadataRelationship>>()
        for (relationship in relationships) {
            val list = relationshipMap[relationship.relationship]
            if (list == null) {
                relationshipMap[relationship.relationship] = mutableListOf(relationship)
            } else {
                list.add(relationship)
            }
        }
        val categories = metadataService.getCategories(item.id)
        val collections = configuration.collections.toCollections(metadataService, item)
        val attributes = configuration.attributes.toAttributes(metadataService, item) ?: JsonObject(emptyMap())
        val references = documentReferences.transform(context, item)
        val books = referencesToBookList.transform(
            context,
            References(
                references = references,
                locale = Locale.forLanguageTag(item.languageTag)
            )
        )
        return MetadataSearchContext(
            metadata = item,
            content = content,
            relationships = relationshipMap,
            categories = categories,
            collections = collections,
            attributes = attributes,
            slug = slugs.getMetadataSlug(item.id) ?: "",
            bibleBooks = json.encodeToJsonElement(books),
            bibleUsfms = references.map { it.usfm }
        )
    }

    companion object {

        private val log = org.slf4j.LoggerFactory.getLogger(MetadataToSearchDocument::class.java)
    }
}