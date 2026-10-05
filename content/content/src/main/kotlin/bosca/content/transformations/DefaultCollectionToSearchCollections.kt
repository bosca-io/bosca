package bosca.content.transformations

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.service.CollectionService
import bosca.search.model.SearchDocumentItem
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DefaultCollectionToSearchCollections : CollectionToSearchCollections {

    override suspend fun toCollections(collectionService: CollectionService, collection: Collection, variant: CollectionLanguageVariant?): Map<String, List<SearchDocumentItem>> {
        val parents = collectionService.getCollectionParents(collection.id).groupByTo(mutableMapOf()) {
            val type = it.attributes?.takeIf { it !is JsonNull }?.jsonObject?.get("type")?.jsonPrimitive?.content?.lowercase() ?: "collection"
            if (type.endsWith("s")) type else "${type}s"
        }
        return parents.mapValues {
            it.value.map { SearchDocumentItem(it.id.toString(), it.name) }
        }
    }
}
