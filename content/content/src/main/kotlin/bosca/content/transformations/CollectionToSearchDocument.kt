package bosca.content.transformations

import bosca.category.model.Category
import bosca.category.service.CategoryService
import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionType
import bosca.content.collection.service.CollectionService
import bosca.content.collection.service.getCategories
import bosca.search.IndexStorageSystem
import bosca.search.model.CollectionSearchContext
import bosca.search.model.CollectionVariantSearchContext
import bosca.search.model.SearchDocumentItem
import bosca.search.model.SearchTransformConfiguration
import bosca.serialization.JsonConverter.toAny
import bosca.serialization.JsonConverter.toJsonElement
import bosca.slug.service.SlugService
import bosca.transformations.Transformation
import com.dashjoin.jsonata.Jsonata
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


interface CollectionToSearchCollections {

    suspend fun toCollections(collectionService: CollectionService, collection: Collection, variant: CollectionLanguageVariant?): Map<String, List<SearchDocumentItem>>
}


interface CollectionToSearchAttributes {

    suspend fun toAttributes(collectionService: CollectionService, collection: Collection, variant: CollectionLanguageVariant?): JsonObject?
}

class CollectionToSearchDocumentConfiguration(
    val collections: CollectionToSearchCollections = DefaultCollectionToSearchCollections(),
    val attributes: CollectionToSearchAttributes = DefaultCollectionToSearchAttributes(),
    val excludeTypes: Set<String> = emptySet(),
    val excludeContentTypePrefix: List<String> = emptyList()
)

class CollectionToSearchDocument(
    private val collectionService: CollectionService,
    private val slugs: SlugService,
    private val json: Json,
    private val configuration: CollectionToSearchDocumentConfiguration,
    private val categoryService: CategoryService,
    private val configurationService: ConfigurationService,
) : Transformation<IndexStorageSystem, Collection, List<JsonElement>> {

    // The null variant represents the base collection and is selected by the default JSONata expression.
    @OptIn(ExperimentalSerializationApi::class)
    private val contextJson = Json(json) {
        explicitNulls = true
    }

    suspend fun toContext(context: IndexStorageSystem, item: Collection, categories: List<Category>): CollectionSearchContext {
        val isAdmin = context.name == "Admin Search Index"
        val variants = mutableListOf<CollectionVariantSearchContext>()
        variants.add(
            CollectionVariantSearchContext(
                variant = null,
                collections = configuration.collections.toCollections(collectionService, item, null),
                attributes = configuration.attributes.toAttributes(collectionService, item, null) ?: JsonObject(emptyMap()),
                slug = slugs.getCollectionSlug(item.id, null)
            )
        )
        collectionService.getLanguageVariants(item.id).forEach { variant ->
            variants.add(
                CollectionVariantSearchContext(
                    variant = variant,
                    collections = configuration.collections.toCollections(collectionService, item, variant),
                    attributes = configuration.attributes.toAttributes(collectionService, item, variant) ?: JsonObject(emptyMap()),
                    slug = slugs.getCollectionSlug(item.id, variant.languageTag)
                )
            )
        }
        return CollectionSearchContext(
            collection = item,
            categories = categories,
            variants = variants,
            slug = slugs.getCollectionSlug(item.id) ?: "",
            isAdmin = isAdmin
        )
    }

    override suspend fun transform(context: IndexStorageSystem, item: Collection): List<JsonElement> {
        if (item.type == CollectionType.SYSTEM || item.type == CollectionType.QUEUE || item.type == CollectionType.ROOT) return emptyList()
        if (context.name != "Admin Search Index") {
            val type = item.attributes?.takeIf { it is JsonObject }?.jsonObject?.get("type")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            if (type != null && type in configuration.excludeTypes) return emptyList()
            if (configuration.excludeContentTypePrefix.any { "bosca/v-collection".startsWith(it) }) return emptyList()
        }
        val categories = collectionService.getCategories(item.id, categoryService)
        val result = toContext(context, item, categories)
        val searchConfiguration = configurationService.getValueAs<SearchTransformConfiguration>("search", json)
        val expression = searchConfiguration?.expressions?.collection
        if (expression.isNullOrBlank()) return listOf(contextJson.encodeToJsonElement(result))
        val transformed = (Jsonata.jsonata(expression).evaluate(contextJson.encodeToJsonElement(result).toAny())).toJsonElement()
        return if (transformed is JsonArray) {
            transformed.toList()
        } else {
            listOf(transformed)
        }
    }
}
